package is.kow.scalatratrackerapp.actors.responders

import akka.actor.{Actor, ActorLogging, Props}
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import com.ullink.slack.simpleslackapi.{SlackAttachment, SlackChannel, SlackPreparedMessage}
import is.kow.scalatratrackerapp.actors.ChannelProjectActor.{ChannelProject, ChannelQuery}
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

  private val channelProjectActor = context.actorSelection("/user/channel-project-actor")

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
    context.system.scheduler.scheduleOnce(1.second, self, SlackTyping(slackChannel))
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

          log.info(s"LOOKING FOR STORY ID $storyId, but getting project IDs for this channel first")
          channelProjectActor ! ChannelQuery(smp.getChannel)
          context.become(awaitingProjectList(smp, storyId))

        } getOrElse {
        //Didn't get a message, we're done
        context.stop(self) //Stop myself
      }
  }

  def awaitingProjectList(smp: SlackMessagePosted, storyId: String): Receive = {
    case ChannelProject(channel, projectIds) =>
      if (projectIds.isEmpty) {
        //nothing!
        context.stop(self)
      } else {
        log.info(s"Received project ids (${projectIds.mkString(", ")}) for channel ${channel.getName}(${
          channel.getId
        })")
        projectIds.foreach { projectId =>
          //Need to hand the project ID to the story detail actor
          log.info(s"Requesting story details for ${storyId.toLong} in $projectId")
          context.actorOf(StoryDetailActor.props) ! StoryDetailsRequest(smp, Left(storyId.toLong), projectId)
          //Await the response from our story detail actor

          import scala.concurrent.duration._

          context.system.scheduler
            .scheduleOnce(45.seconds, self, TrackerStoryTimeout(smp.getChannel))(context.dispatcher, self)
          context.become(awaitingStoryDetailsResponse(projectIds.size))
        }
      }
  }

  def awaitingStoryDetailsResponse(projectsToCheck: Int): Receive = {
    case slackMessage: SlackMessage =>
      //Got a slack message, send it back to the parent and cease to exist
      //We shouldn't need to wait for multiple of these.
      context.parent ! slackMessage
      context.stop(self)

    case SlackTyping(channel) =>
      typing(channel)

    case NoDetails =>
      if (projectsToCheck - 1 == 0) {
        //No details available for any projects
        context.stop(self)
      } else {
        //Tick down, because we got one of them...
        context.become(awaitingStoryDetailsResponse(projectsToCheck - 1))
      }

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
      //We might have more responses (maybe more failures) to talk about
      if (projectsToCheck - 1 == 0) {
        context.stop(self)
      } else {
        context.become(awaitingStoryDetailsResponse(projectsToCheck - 1))
      }
  }
}
