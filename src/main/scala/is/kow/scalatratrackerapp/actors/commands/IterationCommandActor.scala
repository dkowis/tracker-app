package is.kow.scalatratrackerapp.actors.commands

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import com.ullink.slack.simpleslackapi.{SlackAttachment, SlackChannel, SlackPreparedMessage}
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import is.kow.scalatratrackerapp.actors.ChannelProjectActor.{ChannelProject, ChannelProjectTimeout, ChannelQuery}
import is.kow.scalatratrackerapp.actors.SlackBotActor.{CommandPrefix, SlackMessage, SlackTyping}
import is.kow.scalatratrackerapp.actors.pivotal.PivotalRequestActor.{GetIteration, PivotalRequestFailure}
import is.kow.scalatratrackerapp.actors.pivotal.PivotalResponses
import is.kow.scalatratrackerapp.actors.pivotal.PivotalResponses.{PivotalStory, StoryState}
import nl.grons.metrics.scala.DefaultInstrumented
import org.joda.time.format.DateTimeFormat

object IterationCommandActor {
  def props(commandPrefix: CommandPrefix, pivotalRequestActor: ActorRef, channelProjectActor: ActorRef) =
    Props(new IterationCommandActor(commandPrefix, pivotalRequestActor, channelProjectActor))
}


class IterationCommandActor(commandPrefix: CommandPrefix,
                            pivotalRequestActor: ActorRef,
                            channelProjectActor: ActorRef) extends Actor with ActorLogging with DefaultInstrumented {

  val commandRegex = "iteration\\s*$"

  private val iterationsRequested = metrics.counter("iterations.requested")

  import context.dispatcher

  //Send typing, and schedule it again a second later!
  def typing(slackChannel: SlackChannel): Unit = {
    context.parent ! SlackTyping(slackChannel)

    import scala.concurrent.duration._
    //According to the API, every keypress, or in 3 seconds
    context.system.scheduler.scheduleOnce(1.second, self, SlackTyping(slackChannel))
  }

  override def receive: Receive = {
    case smp: SlackMessagePosted =>
      //Need to make this regex a multi-line matching regex
      val fullRegex = s"(?s)${commandPrefix.prefix}$commandRegex".r

      if (fullRegex.pattern.matcher(smp.getMessageContent).matches) {
        //I have now received a request to display iteration metadata

        //I can start typing
        typing(smp.getChannel)
        //need to request the project ID for the channel
        channelProjectActor ! ChannelQuery(smp.getChannel)
        import scala.concurrent.duration._
        context.system.scheduler.scheduleOnce(45.seconds, self, ChannelProjectTimeout)
        context.become(awaitingChannelProject(smp.getChannel))
      } else {
        //didn't match
        log.error(s"Didn't match ${smp.getMessageContent}")
        context.stop(self)
      }
  }

  def awaitingChannelProject(slackChannel: SlackChannel): Receive = {
    case ChannelProject(channelResult, Some(projectId)) =>
      //Got our channel ID back!
      //Request the iteration details from the PivotalRequestActor and become awaiting that, with a timeout
      pivotalRequestActor ! GetIteration(projectId)
      context.become(awaitingIterationDetails(slackChannel))
    //TODO: I think this is reliable enough I don't need a timeout this time
    case ChannelProject(channelResult, None) =>
      //No project associated!
      context.parent ! SlackMessage(
        channel = slackChannel.getId,
        text = Some("I can't get iteration details unless the channel is registered with a project!")
      )
      context.stop(self)
    case ChannelProjectTimeout =>
      //Crap, le timeout!
      log.error("timeout awaiting channel project correlation :(")
      context.parent ! SlackMessage(
        channel = slackChannel.getId,
        text = Some("Sorry, something went wrong, could you try again?)")
      )
      context.stop(self)
    case SlackTyping(channel) =>
      typing(channel)
  }

  def awaitingIterationDetails(slackChannel: SlackChannel): Receive = {
    case iteration: PivotalResponses.Iteration =>
      //Got an iteration, can do fun stuff
      val prettyFormat = DateTimeFormat.forPattern("EEE, MMM d")
      //Display the story states in the results
      val storiesByState: Map[StoryState.State, List[PivotalStory]] = iteration.stories.groupBy(_.currentState)
      val storiesByStateCount: Map[StoryState.State, Int] = storiesByState.map(kv => (kv._1, kv._2.size))
      val acceptedCount = storiesByState(StoryState.accepted).size
      //TODO: this will only find the first iteration release story, need to find all
      val releaseStories = iteration.stories.filter(_.storyType == "release").sortWith { (one, two) =>
        (one.deadline, two.deadline) match {
          case (Some(oneDeadline), Some(otherDeadline)) =>
            oneDeadline.compareTo(otherDeadline) == 1 //Greater than
          case (None, Some(_)) =>
            false
          case (Some(_), None) =>
            true
          case _ => //if one of the deadlines is unset
            false
        }
      }

      //TODO: build a much nicer slack message
      // Story States: accepted, delivered, finished, started, rejected, planned, unstarted, unscheduled
      //TODO: a sorting would be nice for the stories by state count...
      val storyStateCounts = storiesByStateCount.map(kv => s"${kv._1}: *${kv._2}*").mkString(", ")


      ///<http://www.foo.com|www.foo.com>
      val fallback =
        s"""
           |Iteration *${iteration.number}* started on ${prettyFormat.print(iteration.start)} and ends on ${prettyFormat.print(iteration.finish)}
           |Of ${iteration.stories.size} stories: $storyStateCounts
           |Team Strength: ${iteration.teamStrength}
        """.stripMargin.trim + "\n" + releaseStories.map { release =>
          s"<${release.url}|${release.name}> -- " +
            release.deadline.map { deadline =>
              s"Deadline: *${prettyFormat.print(deadline)}* "
            }.getOrElse {
              s"Deadline unset! "
            }
        }.mkString("\n")
      //Build a single attachment with short labels for story types
      val statesAttachment = new SlackAttachment(s"States of ${iteration.stories.size} stories", "TODO: Fallback", storyStateCounts, "")
      storiesByState.foreach{
        case (state, stories) =>
          statesAttachment.addField(state.toString, stories.size.toString, true)
      }

      //String title, String fallback, String text, String pretext
      val slackAttachment = new SlackAttachment(s"Iteration ${iteration.number}", fallback, "text?", s"Iteration ${iteration.number} details")
      slackAttachment.addMiscField("mrkdwn_in", "title") //TODO: this might not be good enough, because it wants an array, doesn't seem to be working

      //TODO: There's some missing bits in the slack prepared message :( formatting, stuff is out of date.
      val builder = new SlackPreparedMessage.Builder()
        .withMessage(s"Iteration ${iteration.number} Details")
        .withUnfurl(false)
        .addAttachment(statesAttachment)

      context.parent ! SlackMessage(
        channel = slackChannel.getId,
        text = Some(fallback)
      )
      context.parent ! SlackMessage(
        channel = slackChannel.getId,
        slackPreparedMessage = Some(builder.build())
      )
      context.stop(self)
    case PivotalRequestFailure(message) =>
      //Relay back the message, because we've failed
      context.parent ! SlackMessage(
        channel = slackChannel.getId,
        text = Some(message)
      )
      context.stop(self)
    case SlackTyping(channel) =>
      typing(channel)
  }
}
