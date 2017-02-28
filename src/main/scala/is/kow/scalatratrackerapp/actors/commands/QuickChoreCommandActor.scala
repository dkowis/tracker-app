package is.kow.scalatratrackerapp.actors.commands

import akka.actor.{Actor, ActorLogging, Props}
import com.ullink.slack.simpleslackapi.SlackChannel
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import is.kow.scalatratrackerapp.actors.QuickChoreCreationActor
import is.kow.scalatratrackerapp.actors.SlackBotActor.{CommandPrefix, SlackMessage, SlackTyping}
import nl.grons.metrics.scala.DefaultInstrumented

object QuickChoreCommandActor {
  def props(commandPrefix: CommandPrefix) = Props(new QuickChoreCommandActor(commandPrefix))
}

class QuickChoreCommandActor(commandPrefix: CommandPrefix) extends Actor with ActorLogging with DefaultInstrumented {

  val commandRegex = "chore\\s+(.*)$"

  //TODO: if they typo the @user, it doesn't get picked up, because it's not highlighted special
  //That's probably okay
  val assignToExtractorRegex = "(?i).*assignTo:\\s+<@(\\w+)>".r
  val assignToRemoverRegex = "(?i)\\s*assignto:\\s+<@\\w+>.*"

  private val choresRequested = metrics.counter("chores.created")
  private val choresCreated = metrics.counter("chores.created")

  //Send typing, and schedule it again a second later!
  def typing(slackChannel: SlackChannel): Unit = {
    context.parent ! SlackTyping(slackChannel)

    import context.dispatcher

    import scala.concurrent.duration._
    //According to the API, every keypress, or in 3 seconds
    context.system.scheduler.scheduleOnce(1 second, self, SlackTyping(slackChannel))
  }

  override def receive: Receive = {
    case smp: SlackMessagePosted =>
      //Need to make this regex a multi-line matching regex
      val fullRegex = s"(?s)${commandPrefix.prefix}$commandRegex".r

      smp.getMessageContent match {
        case fullRegex(content) =>
          //okay I have a pile of content, now I need to split it by \n, grab the first line, and see if there's an assign to
          val firstLine = content.split("\n").head

          val remaining = content.split("\n").tail.mkString("\n")

          val assignToUserName: Option[String] = firstLine match {
            case assignToExtractorRegex(username) =>
              //TODO: have to figure out how to resolve the username right now
              //Can become awaiting username, and do the completion thing
              Some(username)
            case _ =>
              log.debug(s"|$firstLine| didn't match ${assignToExtractorRegex.toString}")
              None
          }

          val title = if (assignToUserName.isDefined) {
            firstLine.replaceAll(assignToRemoverRegex, "").trim
          } else {
            firstLine
          }

          val qcc = QuickChoreCreationActor.QuickCreateChore(smp,
            title,
            remaining,
            assignToUserName)

          choresRequested.inc()
          //At this point, we've got a title, a slack username that is requested to be assigned, and potentially more content
          typing(smp.getChannel)
          context.actorOf(QuickChoreCreationActor.props) ! qcc
          context.become(awaitingQuickChoreCreationResponse)

        case _ =>
        //Didn't match, Nothing to do, exit
          context.stop(self)
      }
  }

  def awaitingQuickChoreCreationResponse: Receive = {
    case slackMessage: SlackMessage =>
      choresCreated.inc()
      context.parent ! slackMessage
      context.stop(self)

    case SlackTyping(channel) =>
      typing(channel)
  }
}
