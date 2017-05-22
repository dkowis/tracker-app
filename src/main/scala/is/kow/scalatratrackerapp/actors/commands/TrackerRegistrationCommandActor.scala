package is.kow.scalatratrackerapp.actors.commands

import akka.actor.{Actor, ActorLogging, Props}
import com.ullink.slack.simpleslackapi.SlackChannel
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import is.kow.scalatratrackerapp.actors.SlackBotActor._
import is.kow.scalatratrackerapp.actors.{ChannelProjectActor, RegistrationActor}

object TrackerRegistrationCommandActor {
  def props(commandPrefix: CommandPrefix) = Props(new TrackerRegistrationCommandActor(commandPrefix))
}

/**
  * registers the @tracker-bot register command to handle that stuff
  * Created by the slackbot actor!
  */
class TrackerRegistrationCommandActor(commandPrefix: CommandPrefix) extends Actor with ActorLogging {

  //TODO: add a regex for de-registering a channel

  val registerRegex = "register(?: +(\\d+))?\\s*"

  //Send typing, and schedule it again a second later!
  def typing(slackChannel: SlackChannel): Unit = {
    context.parent ! SlackTyping(slackChannel)

    import context.dispatcher
    import scala.concurrent.duration._
    //According to the API, every keypress, or in 3 seconds
    context.system.scheduler.scheduleOnce(1.second, self, SlackTyping(slackChannel))
  }


  override def receive: Receive = {
    case smp: SlackMessagePosted =>
      val fullRegex = s"${commandPrefix.prefix}$registerRegex".r

      smp.getMessageContent match {
        case fullRegex(registerProjectId) =>
          log.debug("matched the regex with a capturing group, wrapping it in an option to do stuff")
          Option(registerProjectId) match {
            case Some(projectId) =>
              // A project id was specified
              // set the registration of a channel, notifying that perhaps it changed.
              log.debug(s"Found registerProjectID: $registerProjectId")
              log.debug("registration request sent to registration actor")
              typing(smp.getChannel)
              context.actorOf(RegistrationActor.props) ! RegistrationActor.RegisterChannelRequest(smp, ChannelProjectActor.RegisterChannel(smp.getChannel, registerProjectId.toLong))
              context.become(awaitingRegistrationResponse)

            case None =>
              //No group found
              log.debug("querying for what project is this channel part of")
              log.debug("query request sent to registration actor")
              typing(smp.getChannel)
              context.actorOf(RegistrationActor.props) ! RegistrationActor.ChannelQueryRequest(smp, ChannelProjectActor.ChannelQuery(smp.getChannel))
              context.become(awaitingRegistrationResponse)
          }
        case _ =>
          log.debug("didn't match registration regex, don't care, nothing to send")
          //TERMINATE
          context.stop(self)
      }
  }

  def awaitingRegistrationResponse: Receive = {
    case slackMessage:SlackMessage =>
      context.parent ! slackMessage
      context.stop(self)

    case SlackTyping(channel) =>
      typing(channel)
  }
}
