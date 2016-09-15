package is.kow.scalatratrackerapp.actors.commands

import akka.actor.{Actor, ActorLogging, Props}
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import is.kow.scalatratrackerapp.actors.SlackBotActor.{RegisterRegex, Start, StoryDetailsRequest}
import is.kow.scalatratrackerapp.actors.StoryDetailActor

import scala.util.matching.Regex


object TrackerPatternRegistrationActor {
  def props = Props[TrackerPatternRegistrationActor]

}

/**
  * Encapsulates the Tracker registration pattern and function to handle the regex parsing
  */
class TrackerPatternRegistrationActor extends Actor with ActorLogging {

  val trackerStoryPatterns = List(
    ".*#(\\d+).*".r,
    ".*https://www.pivotaltracker.com/story/show/(\\d+).*".r,
    ".*https://www.pivotaltracker.com/n/projects/\\d+/stories/(\\d+)".r
  )

  val processingFunction: (Regex, SlackMessagePosted) => Option[Any] = (regex, smp) => {
    for {
      regex(storyId) <- regex findFirstIn smp.getMessageContent
    } yield {
      //create an actor for story details, ship it
      //Don't need to give metadata any more, although I could still create that...
      StoryDetailsRequest(smp, storyId.toLong)
    }
  }


  override def receive: Receive = {
    //Should get a start message, and then it responds by sending the registration messages
    case Start =>
      trackerStoryPatterns.foreach { pattern =>
        sender ! RegisterRegex(pattern, StoryDetailActor.props, processingFunction)
      }
      //This is my entire purpose in life, I die
      context.stop(self)
  }
}
