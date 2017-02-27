package is.kow.scalatratrackerapp.actors.commands

import akka.actor.{Actor, ActorLogging, Props}
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import is.kow.scalatratrackerapp.actors.SlackBotActor.{CommandPrefix, RegisterForCommands, Start, StartTyping}
import is.kow.scalatratrackerapp.actors.{ChannelProjectActor, RegistrationActor}

object TrackerRegistrationCommandActor {
  def props(commandPrefix: CommandPrefix) = Props(new TrackerRegistrationCommandActor(commandPrefix))
}

/**
  * registers the @tracker-bot register command to handle that stuff
  */
class TrackerRegistrationCommandActor(commandPrefix: CommandPrefix) extends Actor with ActorLogging {

  //TODO: add a regex for de-registering a channel

  val registerRegex = "register(?: +(\\d+))?\\s*"

  override def receive: Receive = {
    case smp: SlackMessagePosted =>
      val fullRegex = s"$commandPrefix$registerRegex".r

      smp.getMessageContent match {
        case fullRegex(registerProjectId) =>
          log.debug("matched the regex with a capturing group, wrapping it in an option to do stuff")
          Option(registerProjectId) match {
            case Some(projectId) =>
              // A project id was specified
              // set the registration of a channel, notifying that perhaps it changed.
              log.debug(s"Found registerProjectID: $registerProjectId")
              log.debug("registration request sent to registration actor")
              sender ! StartTyping(smp.getChannel)
              context.actorOf(RegistrationActor.props) ! RegistrationActor.RegisterChannelRequest(smp, ChannelProjectActor.RegisterChannel(smp.getChannel, registerProjectId.toLong))

            case None =>
              //No group found
              log.debug("querying for what project is this channel part of")
              log.debug("query request sent to registration actor")
              sender ! StartTyping(smp.getChannel)
              context.actorOf(RegistrationActor.props) ! RegistrationActor.ChannelQueryRequest(smp, ChannelProjectActor.ChannelQuery(smp.getChannel))
          }
        case _ =>
          log.debug("didn't match registration regex, don't care, nothing to send")
      }
  }
}
