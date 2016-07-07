package is.kow.scalatratrackerapp.actors


import akka.actor.{Actor, ActorLogging, Props}
import is.kow.scalatratrackerapp.AppConfig
import is.kow.scalatratrackerapp.json.SlackMessage
import slack.SlackUtil
import slack.models.{Channel, User}
import slack.rtm.SlackRtmClient

/**
  * the actor to connect to slack, and hold open the websocket connection.
  * Translates messages from slack into other messages for other actors
  *
  */

object SlackBotActor {

  def props = Props[SlackBotActor]

  case class StoryDetailsRequest(metadata: MessageMetadata, storyId: Long)

  case class MessageMetadata(channel: Option[Channel], sender: User) {
    val defaultDestination = {
      channel.map(_.id).getOrElse(sender.id)
    }
  }

}

class SlackBotActor extends Actor with ActorLogging {

  import SlackBotActor._
  import slack.models._

  val configuration = AppConfig.config

  val token = configuration.getString("slack.token")

  val client = SlackRtmClient(token)
  client.addEventListener(self)

  def receive = {
    case s: SlackMessage =>
      //Send the message to the client!
      //NOTE: to send pretty messages: https://api.slack.com/methods/chat.postMessage
      val tokenized = s.copy(token = Some(token))
      if (s.attachments.isDefined) {
        context.actorSelection("/user/slack-request-actor") ! tokenized
      } else if (s.text.isDefined) {
        client.sendMessage(s.channel, s.text.get)
      } else {
        //TODO: neither was defined, and thats bad!
      }

    case m: Message => {
      //ZOMG A MESSAGE, lets send
      val mentions = SlackUtil.extractMentionedIds(m.text)
      val metadata = MessageMetadata(
        channel = client.state.channels.find(_.id == m.channel),
        sender = client.state.getUserById(m.user).get //TODO: I cannot ever think of a time I won't get a message from a user
      )

      //TODO: it'd be fun to have the slack bot send the "user is typing" when a command is parsed, because each
      // event should trigger a thing back to slack

      //TODO: build commands and stuff in here.
      //TODO: this is too much to live in here, need to build another subscriber system so that actors can register their commands
      //TODO: also need to create a help actor for when people ask about help

      val trackerStoryPattern = ".*T(\\d+).*".r
      for {
        trackerStoryPattern(storyId) <- trackerStoryPattern findFirstIn m.text
      } yield {
        //create an actor for story details, ship it
        context.actorOf(StoryDetailActor.props) ! StoryDetailsRequest(metadata, storyId.toLong)
      }

      //Also look for a tracker URL and expand up on that
      val trackerUrlPattern = ".*https://www.pivotaltracker.com/story/show/(\\d+).*".r
      for {
        trackerUrlPattern(storyId) <- trackerUrlPattern findFirstIn m.text
      } yield {
        //create an actor for story details, ship it
        context.actorOf(StoryDetailActor.props) ! StoryDetailsRequest(metadata, storyId.toLong)
      }

      //For now handle another command but only when I'm mentioned
      if (mentions.contains(client.state.self.id)) {
        //It's a message to me!
        //NOTE: for some reason the IDs come back in <> and I don't know why
        val mentionPrefix = s"\\s*<@${client.state.self.id}>[:,]?\\s*"
        val registerRegex = s"${mentionPrefix}register(?: +(\\d+))?\\s*".r //The register command with a project id
        log.debug(s"Complete regex: ${registerRegex.toString()}")
        //Send the message to the registration actor and stuff
        log.debug(s"GETTING:   ${m.text} my id: ${client.state.self.id}")

        //TODO: this is no longer pretty, refactor it
        //TODO: also this stuff shouldn't work in a private message, because you can't register that "channel"
        m.text match {
          case registerRegex(registerProjectId) =>
            log.debug("matched the regex with a capturing group, wrapping it in an option to do stuff")
            Option(registerProjectId) match {
              case Some(projectId) =>
                // A project id was specified
                // set the registration of a channel, notifying that perhaps it changed.
                log.debug(s"Found registerProjectID: ${registerProjectId}")
                //TODO: this might not be the right way to do it
                context.actorOf(RegistrationActor.props) ! RegistrationActor.RegisterChannelRequest(metadata, ChannelProjectActor.RegisterChannel(m.channel, registerProjectId.toLong))
                log.debug("registration request sent to registration actor")
              case None =>
                //No group found
                log.debug("querying for what project is this channel part of")
                context.actorOf(RegistrationActor.props) ! RegistrationActor.ChannelQueryRequest(metadata, ChannelProjectActor.ChannelQuery(m.channel))
                log.debug("query request sent to registration actor")
            }
          case _ =>
            log.debug("didn't match registration regex, don't care")
        }

        //TODO: add a regex for de-registering a channel
      }

    }
    case x@_ =>
      //Received some other kind of event
      log.info(s"UNHANDLED -- Received ${x}")
  }
}
