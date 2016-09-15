package is.kow.scalatratrackerapp.actors.commands

import akka.actor.{Actor, ActorLogging, Props}
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import is.kow.scalatratrackerapp.actors.SlackBotActor.{RegisterCommand, Start}
import is.kow.scalatratrackerapp.actors.{ChannelProjectActor, RegistrationActor}

import scala.util.matching.Regex

object TrackerRegistrationCommandActor {
  def props = Props[TrackerRegistrationCommandActor]
}

/**
  * registers the @tracker-bot register command to handle that stuff
  */
class TrackerRegistrationCommandActor extends Actor with ActorLogging {

  //TODO: add a regex for de-registering a channel

  //so many of the backslashes
  val registerRegex = "register(?: +(\\d+))?\\s*"

  val processingFunction: (Regex, SlackMessagePosted) => Option[Any] = { (regex, smp) =>
    smp.getMessageContent match {
      case regex(registerProjectId) =>
        log.debug("matched the regex with a capturing group, wrapping it in an option to do stuff")
        Option(registerProjectId) match {
          case Some(projectId) =>
            // A project id was specified
            // set the registration of a channel, notifying that perhaps it changed.
            log.debug(s"Found registerProjectID: $registerProjectId")
            log.debug("registration request sent to registration actor")
            Some(RegistrationActor.RegisterChannelRequest(smp, ChannelProjectActor.RegisterChannel(smp.getChannel, registerProjectId.toLong)))

          case None =>
            //No group found
            log.debug("querying for what project is this channel part of")
            log.debug("query request sent to registration actor")
            Some(RegistrationActor.ChannelQueryRequest(smp, ChannelProjectActor.ChannelQuery(smp.getChannel)))
        }
      case _ =>
        log.debug("didn't match registration regex, don't care, nothing to send")
        None
    }
  }

  override def receive: Receive = {
    case Start =>
      sender ! RegisterCommand(registerRegex, RegistrationActor.props, processingFunction)

      //Note: not stopping this actor, so that I can use the logging....
      //context.stop(self)
  }
}
