package is.kow.scalatratrackerapp.actors.responders

import akka.actor.{Actor, ActorLogging, Props}
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import is.kow.scalatratrackerapp.actors.SlackBotActor._
import is.kow.scalatratrackerapp.actors.StoryDetailActor
import is.kow.scalatratrackerapp.actors.StoryDetailActor.StoryDetailsRequest

import scala.util.matching.Regex


object TrackerPatternRegistrationActor extends TextResponder {
  def props = Props[TrackerPatternRegistrationActor]

  override val regexes: Seq[Regex] = Seq(
    ".*#(\\d+).*".r,
    ".*https://www.pivotaltracker.com/story/show/(\\d+).*".r,
    ".*https://www.pivotaltracker.com/n/projects/\\d+/stories/(\\d+)".r
  )
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

  override def receive: Receive = {
    //Should get a start message, and then it responds by sending the registration messages
    case Start =>
      sender ! RegisterForMessages()

    case smp: SlackMessagePosted =>
      //see if the message matches one of my messages, and then do something about it
      trackerStoryPatterns.foreach { regex =>
        for {
          regex(storyId) <- regex findFirstIn smp.getMessageContent
        } yield {
          //create an actor for story details, ship it
          sender ! StartTyping(smp.getChannel) //tell the slackbot actor that I'd like to be typing in this channel
          context.actorOf(StoryDetailActor.props) ! StoryDetailsRequest(smp, Left(storyId.toLong))
        }
      }
  }
}
