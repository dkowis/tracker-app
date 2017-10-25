package is.kow.scalatratrackerapp.actors

import akka.actor.{Actor, ActorLogging, Props}
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import is.kow.scalatratrackerapp.actors.ChannelProjectActor.{ChannelQuery, DeregisterChannel, RegisterChannel}
import is.kow.scalatratrackerapp.actors.SlackBotActor.SlackMessage

object RegistrationActor {

  def props = Props[RegistrationActor]

  case class RegisterChannelRequest(slackMessagePosted: SlackMessagePosted, registerChannel: RegisterChannel)

  case class ChannelQueryRequest(slackMessagePosted: SlackMessagePosted, channelQuery: ChannelQuery)

  case class DeregisterChannelRequest(slackMessagePosted: SlackMessagePosted, deregisterChannel: DeregisterChannel)

}

/**
  * This guy should handle the messages and the formatting for channel registration, not the actual database work
  */
class RegistrationActor extends Actor with ActorLogging {

  import RegistrationActor._

  private val channelProjectActor = context.actorSelection("/user/channel-project-actor")
  private val parentActor = context.parent

  var slackMessagePosted: Option[SlackMessagePosted] = None

  def receive = {
    case r: RegisterChannelRequest =>
      log.debug(s"Received channel registration request: ${r.registerChannel}")
      slackMessagePosted = Some(r.slackMessagePosted)
      channelProjectActor ! r.registerChannel
      log.debug("Becoming awaiting response!")
      context.become(awaitingResponse)

    case request: ChannelQueryRequest =>
      log.debug("received channel query request")
      slackMessagePosted = Some(request.slackMessagePosted)
      channelProjectActor ! request.channelQuery
      log.debug("Becoming awaiting response")
      context.become(awaitingResponse)

    case deregister: DeregisterChannelRequest =>
      slackMessagePosted = Some(deregister.slackMessagePosted)
      channelProjectActor ! deregister.deregisterChannel
      context.become(awaitingResponse)
  }

  def awaitingResponse: Actor.Receive = {
    case cp: ChannelProjectActor.ChannelProject =>
      val channelName = slackMessagePosted.get.getChannel.getName
      val channelId = slackMessagePosted.get.getChannel.getId
      val channelText = s"<#$channelId|$channelName>"

      log.debug(s"Received response: $cp")
      if (cp.projectIds.isEmpty) {
        parentActor ! SlackMessage(
          channel = slackMessagePosted.get.getChannel.getId, //TODO: need to have a way to find the default destination
          text = Some(s"Channel $channelText is not associated with any Tracker Project")
        )
      } else {
        val projectsList = cp.projectIds.map { projectId =>
          s"* https://www.pivotaltracker.com/n/projects/$projectId"
        }

        parentActor ! SlackMessage(
          channel = slackMessagePosted.get.getChannel.getId,
          text = Some(
            s"""
               |Channel $channelText is associated with the folowing tracker projects:
               |${projectsList.mkString("\n")}
            """.stripMargin)
        )
      }
      log.debug("sent response! I'm done")
      context.stop(self)
  }
}
