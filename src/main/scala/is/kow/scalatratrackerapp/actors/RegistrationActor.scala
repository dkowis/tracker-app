package is.kow.scalatratrackerapp.actors

import akka.actor.{Actor, ActorLogging, Props}
import is.kow.scalatratrackerapp.actors.ChannelProjectActor.{ChannelQuery, DeregisterChannel, RegisterChannel}
import is.kow.scalatratrackerapp.actors.SlackBotActor.MessageMetadata
import is.kow.scalatratrackerapp.json.SlackMessage

object RegistrationActor {

  def props = Props[RegistrationActor]

  case class RegisterChannelRequest(metadata: MessageMetadata, registerChannel: RegisterChannel)

  case class ChannelQueryRequest(metadata: MessageMetadata, channelQuery: ChannelQuery)

  case class DeregisterChannelRequest(metadata: MessageMetadata, deregisterChannel: DeregisterChannel)

}

/**
  * This guy should handle the messages and the formatting for channel registration, not the actual database work
  */
class RegistrationActor extends Actor with ActorLogging {

  import RegistrationActor._

  val channelProjectActor = context.actorSelection("/user/channel-project-actor")
  val slackBotActor = context.actorSelection("/user/slack-bot-actor")

  var slackMetadata: Option[MessageMetadata] = None

  def receive = {
    case r: RegisterChannelRequest =>
      log.debug(s"Received channel registration request: ${r.registerChannel}")
      slackMetadata = Some(r.metadata)
      channelProjectActor ! r.registerChannel
      log.debug("Becoming awaiting response!")
      context.become(awaitingResponse)

    case request: ChannelQueryRequest =>
      log.debug("received channel query request")
      slackMetadata = Some(request.metadata)
      channelProjectActor ! request.channelQuery
      log.debug("Becoming awaiting response")
      context.become(awaitingResponse)

    case deregister: DeregisterChannelRequest =>
      slackMetadata = Some(deregister.metadata)
      channelProjectActor ! deregister.deregisterChannel
      context.become(awaitingResponse)
  }

  def awaitingResponse: Actor.Receive = {
    case cp: ChannelProjectActor.ChannelProject =>
      val channelName = slackMetadata.get.channel.get.name
      val channelId = slackMetadata.get.channel.get.id
      val channelText = s"<#$channelId|$channelName>"

      log.debug(s"Received response: $cp")
      cp.projectId.map { projectId =>
        //Craft responses

        slackBotActor ! SlackMessage(
          channel = slackMetadata.get.defaultDestination,
          //TODO: this baseURL should come from config
          text = Some(s"Channel $channelText is associated with Tracker Project https://www.pivotaltracker.com/n/projects/${cp.projectId.get}")
        )
      } getOrElse {
        slackBotActor ! SlackMessage(
          channel = slackMetadata.get.defaultDestination,
          text = Some(s"Channel $channelText is not associated with any Tracker Project")
        )
      }

      log.debug("sent response! I'm done")
      context.stop(self)
  }
}
