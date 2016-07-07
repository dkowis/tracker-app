package is.kow.scalatratrackerapp.actors

import akka.actor.{Actor, ActorLogging, Props}
import com.typesafe.config.ConfigFactory
import is.kow.scalatratrackerapp.AppConfig
import is.kow.scalatratrackerapp.actors.PivotalRequestActor.{Labels, StoryDetails}
import is.kow.scalatratrackerapp.actors.SlackBotActor.StoryDetailsRequest
import is.kow.scalatratrackerapp.json._
import org.joda.time.DateTime
import play.api.Configuration
import play.api.libs.json.JsError


object StoryDetailActor {
  def props = Props[StoryDetailActor]

}

class StoryDetailActor extends Actor with ActorLogging {

  val pivotalRequestActor = context.actorSelection("/user/pivotal-request-actor")
  val channelProjectActor = context.actorSelection("/user/channel-project-actor")
  val slackBotActor = context.actorSelection("/user/slack-bot-actor")

  val config = AppConfig.config

  //Maintain some internal state for the story details
  //This actor should be created for each time someone wants to get tracker details!
  var storyOption: Option[PivotalStory] = None
  var labelsOption: Option[List[PivotalLabel]] = None
  var request: Option[StoryDetailsRequest] = None
  //Double boxed, because the channel might not be associated with a project yet....
  var channelProjectId: Option[Option[Long]] = None

  def receive = {
    case r: StoryDetailsRequest =>
      //Got a request for story details! ask for it and become waiting on it, and maybe schedule a timeout
      if(r.metadata.channel.isDefined) {
        request = Some(r)
        channelProjectActor ! ChannelProjectActor.ChannelQuery(r.metadata.channel.get.id)
        log.debug("Requesting a project id from the derterbers")
        context.become(awaitingProjectId)
      } else {
        slackBotActor ! SlackMessage(
          channel = r.metadata.defaultDestination,
          text = Some("Unable to get story details without a channel context, sorry! (ask in the channel)")
        )
        context.stop(self)
      }
  }

  def awaitingProjectId: Actor.Receive = {
    case p: ChannelProjectActor.ChannelProject =>
      //channelProjectId = Some(p.projectId)
      p.projectId.map { projectId =>
        val storyDetails = StoryDetails(projectId, request.get.storyId)
        pivotalRequestActor ! storyDetails
        log.debug("Asked for story details")
        pivotalRequestActor ! Labels(projectId) //Duh, also ask for the labels
        log.debug("Also asked for labels")
        log.debug("Becoming awaiting response")
        //TODO: add a timer to catch timeouts
        context.become(awaitingResponse)
      } getOrElse {
        //We don't have a project ID, so we cannot continue, stopping self.
        slackBotActor ! SlackMessage(
          channel = request.get.metadata.channel.get.id,
          text = Some("I'm sorry, but this channel isn't registered to a project. Use `register <project-id>` to associate it!")
        )
        //TODO: a second exit point? probably not that great.
        //It's all over
        context.stop(self)
      }
  }

  def awaitingResponse: Actor.Receive = {
    case s: PivotalStory =>
      storyOption = Some(s)
      //Check to see if I've got my things, and die
      log.debug("got my story details!")
      craftResponse()
    case e: JsError =>
    //TODO: something bad happened
    case l: List[PivotalLabel] =>
      labelsOption = Some(l)
      log.debug("got my label list")
      //Check to see if I've got both my things, and craft response and then die
      craftResponse()
  }

  def craftResponse(): Unit = {
    log.debug("Maybe I can craft a response")
    for {
      story <- storyOption
      labels <- labelsOption
    } yield {
      log.debug("ITS HAPPENING")
      //Got both of the things, craft the response, and then terminate myself
      val labelText: String = story.labels.flatMap { label =>
        labels.find(l => label.id == l.id).map { l =>
          l.name
        }
      }.mkString(", ")

      //get my url from the configs
      val vcapApplication = ConfigFactory.parseString(config.getString("vcap_application"))
      val host = vcapApplication.getStringList("application_uris").get(0) //get the first one

      //I always want to send my response directly to the slack bot actor
      slackBotActor ! SlackMessage(
        channel = request.get.metadata.defaultDestination,
        attachments = Some(List(SlackAttachment(
          title = s"${story.id} - ${story.name}",
          fallback = story.name,
          title_link = Some(story.url),
          text = story.description.getOrElse(""), // If there's no description, just return ""
          fields = Some(List(
            SlackField(title = "State", value = story.currentState, short = true),
            SlackField(title = "Type", value = story.storyType, short = true),
            SlackField(title = "Labels", value = labelText, short = false)
          )),
          footer = Some("TrackerApp - updated at"),
          ts = Some(DateTime.parse(story.updatedAt).getMillis),
          footerIcon = Some(s"http://$host/assets/images/Tracker_Icon.svg")
        ))),
        asUser = Some(true)
      )

      //Stop myself, I'm done
      log.debug("I replied, stopping myself")
      context.stop(self)
    }
  }
}
