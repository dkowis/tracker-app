package actors

import actors.RequestActor.StoryDetails
import actors.SlackBotActor.StoryDetailsRequest
import akka.actor.{Actor, Props}
import org.joda.time.DateTime
import play.api.libs.json.JsError
import services._


object StoryDetailActor {
  def props = Props[StoryDetailActor]

}

class StoryDetailActor extends Actor {

  //TODO: I think this works, so that I don't have to deal with dependency injection
  // Dependency injected actors is frustrating
  val requestActor = context.actorSelection("../request-actor")

  //Maintain some internal state for the story details
  //This actor should be created for each time someone wants to get tracker details!
  var storyOption: Option[PivotalStory] = None
  var labelsOption: Option[List[PivotalLabel]] = None
  var request: Option[StoryDetailsRequest] = None

  def receive = {
    case r: StoryDetailsRequest =>
      //Got a request for story details! ask for it and become waiting on it, and maybe schedule a timeout
      requestActor ! r.storyDetails
      request = Some(r)

  }

  def awaitingResponse: Actor.Receive = {
    case s: PivotalStory =>
      storyOption = Some(s)
    //Check to see if I've got my things, and die
    case e: JsError =>
    //TODO: something bad happened
    case l: List[PivotalLabel] =>
      labelsOption = Some(l)
    //Check to see if I've got both my things, and craft response and then die
  }

  def craftResponse(): Unit = {
    for {
      story <- storyOption
      labels <- labelsOption
    } yield {
      //Got both of the things, craft the response, and then terminate myself
      val labelText: String = story.labels.flatMap { label =>
        labels.find(l => label.id == l.id)
      }.mkString(", ")

      sender() ! SlackMessage(channel = request.get.channel,
        attachments = List(SlackAttachment(
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
        ))
      )

      //Stop myself, I'm done
      context.stop(self)
    }
  }
}
