package is.kow.scalatratrackerapp.actors.responders

import akka.actor.{Actor, ActorLogging, Props}
import com.ullink.slack.simpleslackapi.{SlackAttachment, SlackChannel, SlackPreparedMessage}
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import is.kow.scalatratrackerapp.actors.SlackBotActor._
import is.kow.scalatratrackerapp.actors.StoryDetailActor
import is.kow.scalatratrackerapp.actors.StoryDetailActor.{NoDetails, StoryDetailsRequest}
import is.kow.scalatratrackerapp.actors.responders.TrackerStoryPatternActor.TrackerStoryTimeout

object TrackerStoryPatternActor {
  def props = Props[TrackerStoryPatternActor]

  case class TrackerStoryTimeout(slackChannel: SlackChannel)
}

/**
  * Encapsulates the Tracker registration pattern and function to handle the regex parsing
  */
class TrackerStoryPatternActor extends Actor with ActorLogging {

  val trackerStoryPatterns = List(
    ".*#(\\d+).*".r,
    ".*https://www.pivotaltracker.com/story/show/(\\d+).*".r,
    ".*https://www.pivotaltracker.com/n/projects/\\d+/stories/(\\d+)".r
  )


  //Send typing, and schedule it again a second later!
  def typing(slackChannel: SlackChannel): Unit = {
    context.parent ! SlackTyping(slackChannel)

    import context.dispatcher
    import scala.concurrent.duration._
    //According to the API, every keypress, or in 3 seconds
    context.system.scheduler.scheduleOnce(1 second, self, SlackTyping(slackChannel))
  }

  override def receive: Receive = {
    case SlackTyping(channel) =>
      typing(channel)

    case smp: SlackMessagePosted =>
      //see if the message matches one of my messages, and then do something about it
      trackerStoryPatterns
        .find(p => p.findFirstIn(smp.getMessageContent).isDefined)
        .flatMap { pattern =>
          pattern.findFirstMatchIn(smp.getMessageContent)
        }
        .map { regexMatch =>
          //Handle the typing scheduling in this guy!, so if it crashes, it stops!
          val storyId = regexMatch.group(1)
          typing(smp.getChannel())
          log.info(s"LOOKING FOR STORY ID $storyId")
          context.actorOf(StoryDetailActor.props) ! StoryDetailsRequest(smp, Left(storyId.toLong))
          //Await the response from our story detail actor
          //TODO: this needs some kind of timeout, we may never get that response....
          //This seems to be the source of the bug of typing death
          import context.dispatcher
          import scala.concurrent.duration._

          context.system.scheduler.scheduleOnce(45.seconds, self, TrackerStoryTimeout(smp.getChannel))
          context.become(awaitingSlackMessage)
        } getOrElse {
        //Didn't get a message, we're done
        context.stop(self) //Stop myself
      }
  }

  def awaitingSlackMessage: Receive = {
    case slackMessage: SlackMessage =>
      //Got a slack message, send it back to the parent and cease to exist
      context.parent ! slackMessage
      context.stop(self)

    case SlackTyping(channel) =>
      typing(channel)

    case NoDetails =>
      //No details available, just quit
      context.stop(self)

    case TrackerStoryTimeout(channel) =>
      val failureMessage = new SlackAttachment("Debug Output", "FAILURE - Debug Output", "Did not receive any response within 45 seconds in `TrackerStoryPatternActor`", "FAILURE")

      val spm = new SlackPreparedMessage.Builder()
        .addAttachment(failureMessage)
        .withUnfurl(false)
        .build()

      context.parent ! SlackMessage(
        channel = channel.getId,
        slackPreparedMessage = Some(spm)
      )
      context.stop(self)
  }
}
