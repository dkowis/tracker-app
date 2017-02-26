package is.kow.scalatratrackerapp.actors.commands

import akka.actor.{Actor, ActorLogging, Props}
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import is.kow.scalatratrackerapp.actors.QuickChoreCreationActor
import is.kow.scalatratrackerapp.actors.SlackBotActor.{CommandPrefix, RegisterForCommands, Start, StartTyping}

object UnstartedChoreCommandActor extends CommandResponder {
  def props = Props[UnstartedChoreCommandActor]

  override val commandRegex = "unstarted-chore\\s+(.*)$"
}

class UnstartedChoreCommandActor extends Actor with ActorLogging {
  val commandRegex = "unstarted-chore\\s+(.*)$"

  //TODO: if they typo the @user, it doesn't get picked up, because it's not highlighted special
  //That's probably okay
  val assignToExtractorRegex = "(?i).*assignTo:\\s+<@(\\w+)>".r
  val assignToRemoverRegex = "(?i)\\s*assignto:\\s+<@\\w+>.*"

  var commandPrefix: String = _

  override def receive: Receive = {
    case Start =>
      sender ! RegisterForCommands()
      context.become(awaitingCommandPrefix)
  }

  def awaitingCommandPrefix: Receive = {
    case c: CommandPrefix =>
      commandPrefix = c.prefix
      context.become(readyToServe)
  }

  def readyToServe: Receive = {
    case smp: SlackMessagePosted =>
      //Need to make this regex a multi-line matching regex
      val fullRegex = s"(?s)$commandPrefix$commandRegex".r

      smp.getMessageContent match {
        case fullRegex(content) =>
          //okay I have a pile of content, now I need to split it by \n, grab the first line, and see if there's an assign to
          val firstLine = content.split("\n").head

          val remaining = content.split("\n").tail.mkString("\n")

          val assignToUserName: Option[String] = firstLine match {
            case assignToExtractorRegex(username) =>
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

          //At this point, we've got a title, a slack username that is requested to be assigned, and potentially more content
          sender ! StartTyping(smp.getChannel) //Start typing in this channel!
          context.actorOf(QuickChoreCreationActor.props) ! qcc

        case _ =>
        //Didn't match, don't care
      }
  }

}
