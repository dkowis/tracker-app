package actors

import actors.PivotalRequestActor.{Labels, StoryDetails}
import actors.SlackBotActor.StoryDetailsRequest
import akka.actor.{Actor, ActorLogging, Props}
import org.joda.time.DateTime
import play.api.libs.json.JsError
import services._


object StoryDetailActor {
  def props = Props[StoryDetailActor]

}

class StoryDetailActor extends Actor with ActorLogging{

  //TODO: I think this works, so that I don't have to deal with dependency injection
  // Dependency injected actors is frustrating
  val pivotalRequestActor = context.actorSelection("/user/pivotal-request-actor")

  //Maintain some internal state for the story details
  //This actor should be created for each time someone wants to get tracker details!
  var storyOption: Option[PivotalStory] = None
  var labelsOption: Option[List[PivotalLabel]] = None
  var request: Option[StoryDetailsRequest] = None

  def receive = {
    case r: StoryDetailsRequest =>
      //Got a request for story details! ask for it and become waiting on it, and maybe schedule a timeout
      pivotalRequestActor ! r.storyDetails
      log.debug("Asked for story details")
      pivotalRequestActor ! Labels(r.storyDetails.projectId) //Duh, also ask for the labels
      log.debug("Also asked for labels")
      request = Some(r)
      log.debug("Becoming awaiting response")
      context.become(awaitingResponse)
      //TODO: add a timer to catch timeouts
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
        labels.find(l => label.id == l.id).map {_.name }
      }.mkString(", ")

      //TODO: is this the right sender? Probably parent
      //TODO: should be parent, whomever created me. I hope
      context.parent ! SlackMessage(
        channel = request.get.channel,
        attachments = Some(List(SlackAttachment(
          title = story.name,
          fallback = story.name,
          title_link = Some(story.url),
          text = story.description, //TODO: maybe render this via markdown into HTMLs?
          fields = Some(List(
            SlackField(title = "State", value = story.currentState, short = true),
            SlackField(title = "Type", value = story.storyType, short = true),
            SlackField(title = "Labels", value = labelText, short = false)
          )),
          footer = Some("TrackerApp - updated at"),
          ts = Some(DateTime.parse(story.updatedAt).getMillis),
          footerIcon = Some("/assets/images/Tracker_Icon.svg") //TODO: figure out how to get the proper hostname
        ))),
        asUser = Some(true)
      )

      //Stop myself, I'm done
      log.debug("I replied, stopping myself")
      context.stop(self)
    }
  }
}
