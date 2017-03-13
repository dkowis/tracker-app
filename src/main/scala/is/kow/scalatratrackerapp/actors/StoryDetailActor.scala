package is.kow.scalatratrackerapp.actors

import akka.actor.{Actor, ActorLogging, Props}
import com.typesafe.config.ConfigFactory
import com.ullink.slack.simpleslackapi.{SlackAttachment, SlackPreparedMessage}
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import is.kow.scalatratrackerapp.AppConfig
import is.kow.scalatratrackerapp.actors.SlackBotActor.{SlackMessage, SlackTyping}
import is.kow.scalatratrackerapp.actors.StoryDetailActor.{NoDetails, SelfTimeout, StoryDetailsRequest}
import is.kow.scalatratrackerapp.actors.pivotal.PivotalRequestActor.{Labels, LabelsList, StoryDetails, StoryNotFound}
import is.kow.scalatratrackerapp.actors.pivotal.{PivotalError, PivotalLabel, PivotalStory}
import play.api.libs.json.JsError


object StoryDetailActor {
  def props = Props[StoryDetailActor]

  case class StoryDetailsRequest(
                                  slackMessagePosted: SlackMessagePosted,
                                  story: Either[Long, PivotalStory]
                                )

  case object NoDetails
  case object SelfTimeout

}

class StoryDetailActor extends Actor with ActorLogging {

  private val pivotalRequestActor = context.actorSelection("/user/pivotal-request-actor")
  private val channelProjectActor = context.actorSelection("/user/channel-project-actor")
  private val myParent = context.parent

  private val config = AppConfig.config

  //Maintain some internal state for the story details
  //This actor should be created for each time someone wants to get tracker details!
  var storyOption: Option[PivotalStory] = None
  var labelsOption: Option[List[PivotalLabel]] = None
  var request: Option[StoryDetailsRequest] = None
  //Double boxed, because the channel might not be associated with a project yet....
  var channelProjectId: Option[Option[Long]] = None

  var storyId: Option[Long] = None

  def receive = {
    case r: StoryDetailsRequest =>
      request = Some(r)

      //TODO: could pattern match on this to extract it a bit cleaner
      if(r.story.isLeft) {
        //Set the story for our use later
        storyId = Some(r.story.left.get)
        //Got a request for story details! ask for it and become waiting on it, and maybe schedule a timeout
        //Because java, this could be null?
        if(Option(r.slackMessagePosted.getChannel).isDefined) {
          channelProjectActor ! ChannelProjectActor.ChannelQuery(r.slackMessagePosted.getChannel)
          log.debug("Requesting a project id from the derterbers")
          context.become(awaitingProjectId)
        } else {
          myParent ! SlackMessage(
            //TODO: need to figure out how to mix in a default destination thingy
            channel = r.slackMessagePosted.getSender.getId,
            text = Some("Unable to get story details without a channel context, sorry! (ask in the channel)")
          )
          context.stop(self)
        }
      } else {
        //A request for fully populated story details, when I've already got a story!
        val pivotalStory = r.story.right.get
        storyOption = Some(pivotalStory)
        //ask for labels, and become awaitingResponse
        pivotalRequestActor ! Labels(pivotalStory.projectId)
        context.become(awaitingResponse)
      }
  }

  def awaitingProjectId: Actor.Receive = {
    case p: ChannelProjectActor.ChannelProject =>
      //channelProjectId = Some(p.projectId)
      p.projectId.map { projectId =>
        val storyDetails = StoryDetails(projectId, storyId.get)
        pivotalRequestActor ! storyDetails
        log.debug("Asked for story details")
        pivotalRequestActor ! Labels(projectId) //Duh, also ask for the labels
        log.debug("Also asked for labels")
        log.debug("Becoming awaiting response")

        import context.dispatcher
        import scala.concurrent.duration._
        //Schedule a timeout message 30 seconds from now
        context.system.scheduler.scheduleOnce(30 second, self, SelfTimeout)

        context.become(awaitingResponse)
      } getOrElse {

        //We don't have a project ID, so we cannot continue, stopping self.
        myParent ! SlackMessage(
          channel = request.get.slackMessagePosted.getChannel.getId,
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
    //TODO: Need a better error protocol than this
      stopTrying(Some(e))
    case StoryNotFound =>
      stopTrying()
    case p:PivotalError =>
      log.debug(s"Got the pivotal error to report back to slack: $p")
      stopTrying()
    case LabelsList(l) =>
      labelsOption = Some(l)
      log.debug("got my label list")
      //Check to see if I've got both my things, and craft response and then die
      craftResponse()
    case SelfTimeout =>
      //Got a timeout, prepare my failure message and send it, and then die!
      val failureMessage = new SlackAttachment("Debug Output", "FAILURE - Debug Output", "Did not recieve all my responses within 30 seconds", "FAILURE")
      failureMessage.addField("story", storyOption.isDefined.toString, true)
      failureMessage.addField("labels", labelsOption.isDefined.toString, true)
      if(request.get.story.isLeft) {
         failureMessage.addField("storyRequested", request.get.story.left.get.toString, true)
      } else {
        failureMessage.addField("fullStoryRequested", request.get.story.right.get.id.toString, true)
      }

      val spm = new SlackPreparedMessage.Builder()
        .addAttachment(failureMessage)
        .withUnfurl(false)
        .build()

      myParent ! SlackMessage(
        channel = request.get.slackMessagePosted.getChannel.getId,
        slackPreparedMessage = Some(spm)
      )
      context.stop(self)
  }

  def stopTrying(e:Option[JsError] = None): Unit = {
    log.debug("got an error back, so we're going to give up")
    myParent ! NoDetails
    context.stop(self)
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
      //Build the attachment first
      val storyAttachment = new com.ullink.slack.simpleslackapi.SlackAttachment(s"${story.id} - ${story.name}", story.name, story.description.getOrElse(""), "") //No pretext

      storyAttachment.addField("State", story.currentState, true)
      storyAttachment.addField("Type", story.storyType, true)
      storyAttachment.addField("Labels", labelText, false)
      storyAttachment.setFooter("TrackerApp")
      storyAttachment.setFooterIcon(s"http://$host/assets/images/Tracker_Icon.svg")
      storyAttachment.setTitleLink(story.url)
      //TODO: add the timestamp field on there

      val spm = new SlackPreparedMessage.Builder()
        .addAttachment(storyAttachment)
        .withUnfurl(false)
        .build()
      //TODO: this should go back to the original sender instead?
      myParent ! SlackMessage(channel = request.get.slackMessagePosted.getChannel.getId, slackPreparedMessage = Some(spm))

      //Stop myself, I'm done
      log.debug("I replied, stopping myself")
      context.stop(self)
    }
  }
}
