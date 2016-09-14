package is.kow.scalatratrackerapp.actors


import akka.actor.{Actor, ActorLogging, Props}
import com.ullink.slack.simpleslackapi.SlackPersona.SlackPresence
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import com.ullink.slack.simpleslackapi.impl.SlackSessionFactory
import com.ullink.slack.simpleslackapi.listeners.SlackMessagePostedListener
import com.ullink.slack.simpleslackapi.{SlackPreparedMessage, SlackSession}
import is.kow.scalatratrackerapp.AppConfig
import is.kow.scalatratrackerapp.json.SlackMessage

/**
  * the actor to connect to slack, and hold open the websocket connection.
  * Translates messages from slack into other messages for other actors
  *
  */

object SlackBotActor {

  def props = Props[SlackBotActor]

  case class StoryDetailsRequest(slackMessagePosted: SlackMessagePosted, storyId: Long)

  case object Start

}

class SlackBotActor extends Actor with ActorLogging {

  import SlackBotActor._

  val configuration = AppConfig.config

  val token = configuration.getString("slack.token")

  //Have to have some mutable state for the client, because it can time out and fail to start....
  var session: SlackSession = null //TODO: GASP IM USING A NULL

  self ! Start //tell myself to start every time I'm created

  def receive = {
    case Start =>
      //if I'm configured with a proxy, make it go
      session = if (configuration.getString("https.proxyHost").isEmpty) {
        log.info("Connecting WIHTOUT proxy")
        SlackSessionFactory.createWebSocketSlackSession(token)
      } else {
        val proxyHost = configuration.getString("https.proxyHost")
        val proxyPort = configuration.getInt("https.proxyPort")
        log.info(s"Connecting WITH proxy: ${proxyHost}:${proxyPort}")
        SlackSessionFactory.createWebSocketSlackSession(token, java.net.Proxy.Type.HTTP, proxyHost, proxyPort)
      }
      session.connect()

      //Set my presence to auto, because why not
      session.setPresence(SlackPresence.AUTO)
      //I can subscribe to other events eventually
      //This just gets me message postings, which is a good enough start
      session.addMessagePostedListener(new SlackMessagePostedListener() {
        override def onEvent(event: SlackMessagePosted, session: SlackSession): Unit = {
          //send the event to this actor
          self ! event
        }
      })

      context.become(readyForService)
  }

  def readyForService: Actor.Receive = {
    //TODO: replace this with something completely different
    case s: SlackMessage =>
      //Send the message to the client!
      //NOTE: to send pretty messages: https://api.slack.com/methods/chat.postMessage
      val tokenized = s.copy(token = Some(token))
      //TODO this is extra super brittle! assumes always a channel
      val channel = Option(session.findChannelById(s.channel)).getOrElse {
        session.findChannelByName(s.channel)
      }
      log.debug(s"Attempting to send message: ${s}")
      if (s.slackPreparedMessage.isDefined) {
        session.sendMessage(channel, s.slackPreparedMessage.get)
      } else if (s.text.isDefined) {
        session.sendMessage(channel, s.text.get)
      } else {
        //TODO: neither was defined, and thats bad!
      }

    case spa: SlackPreparedMessage =>
      //also send the prepared message
      log.debug("Sending prepared message, eventually!")

    case smp: SlackMessagePosted => {
      //ZOMG A MESSAGE, lets send
      val botPersona = session.sessionPersona()
      val mentioned = smp.getMessageContent.contains(s"<@${botPersona.getId}>")
      log.debug(s"Looking for a mention of me: <@${botPersona.getId}> -> ${mentioned}")
      log.debug(s"MESSAGE RECEIVED: ${smp.getMessageContent}")
      //need to filter out messages the bot itself sent, because we don't want those
      if (smp.getSender.getId != botPersona.getId) {
        log.debug("the message didn't come from me!")
        //TODO: it'd be fun to have the slack bot send the "user is typing" when a command is parsed, because each
        // event should trigger a thing back to slack

        //TODO: build commands and stuff in here.
        //TODO: this is too much to live in here, need to build another subscriber system so that actors can register their commands
        //TODO: also need to create a help actor for when people ask about help

        //TODO: the tracker story ID is just a number with lots of digits
        val trackerStoryPattern = ".*#(\\d+).*".r
        for {
          trackerStoryPattern(storyId) <- trackerStoryPattern findFirstIn smp.getMessageContent
        } yield {
          session.sendTyping(smp.getChannel) //TODO a bit brittle, but awesome!
          //create an actor for story details, ship it
          //Don't need to give metadata any more, although I could still create that...
          context.actorOf(StoryDetailActor.props) ! StoryDetailsRequest(smp, storyId.toLong)
        }

        //Also look for a tracker URL and expand up on that
        // https://www.pivotaltracker.com/n/projects/1580895/stories/127817127 that is also a valid url
        val trackerUrlPattern = ".*https://www.pivotaltracker.com/story/show/(\\d+).*".r
        for {
          trackerUrlPattern(storyId) <- trackerUrlPattern findFirstIn smp.getMessageContent
        } yield {
          //create an actor for story details, ship it
          session.sendTyping(smp.getChannel) //TODO a bit brittle, but awesome!
          context.actorOf(StoryDetailActor.props) ! StoryDetailsRequest(smp, storyId.toLong)
        }

        val trackerLongUrlPattern = ".*https://www.pivotaltracker.com/n/projects/\\d+/stories/(\\d+)".r
        for {
          trackerLongUrlPattern(storyId) <- trackerLongUrlPattern findFirstIn smp.getMessageContent
        } yield {
          //Story details time!
          session.sendTyping(smp.getChannel) //TODO a bit brittle, but awesome!
          context.actorOf(StoryDetailActor.props) ! StoryDetailsRequest(smp, storyId.toLong)
        }

        //For now handle another command but only when I'm mentioned
        if (mentioned) {
          //It's a message to me!
          //NOTE: for some reason the IDs come back in <> and I don't know why
          val mentionPrefix = s"\\s*<@${botPersona.getId}>[:,]?\\s*"
          val registerRegex = s"${mentionPrefix}register(?: +(\\d+))?\\s*".r //The register command with a project id
          log.debug(s"Complete regex: ${registerRegex.toString()}")
          //Send the message to the registration actor and stuff
          log.debug(s"GETTING:   ${smp.getMessageContent} my id: ${botPersona.getId}")

          //TODO: this is no longer pretty, refactor it
          //TODO: also this stuff shouldn't work in a private message, because you can't register that "channel"
          smp.getMessageContent match {
            case registerRegex(registerProjectId) =>
              log.debug("matched the regex with a capturing group, wrapping it in an option to do stuff")
              Option(registerProjectId) match {
                case Some(projectId) =>
                  // A project id was specified
                  // set the registration of a channel, notifying that perhaps it changed.
                  log.debug(s"Found registerProjectID: ${registerProjectId}")
                  session.sendTyping(smp.getChannel) //TODO a bit brittle, but awesome!
                  //TODO: this might not be the right way to do it
                  context.actorOf(RegistrationActor.props) ! RegistrationActor.RegisterChannelRequest(smp, ChannelProjectActor.RegisterChannel(smp.getChannel, registerProjectId.toLong))
                  log.debug("registration request sent to registration actor")
                case None =>
                  //No group found
                  log.debug("querying for what project is this channel part of")
                  session.sendTyping(smp.getChannel) //TODO a bit brittle, but awesome!
                  context.actorOf(RegistrationActor.props) ! RegistrationActor.ChannelQueryRequest(smp, ChannelProjectActor.ChannelQuery(smp.getChannel))
                  log.debug("query request sent to registration actor")
              }
            case _ =>
              log.debug("didn't match registration regex, don't care")
          }

          //TODO: add a regex for de-registering a channel
        }
      } else {
        //it's a message from myself, that's okay, don't care
      }
    }
    case x@_ =>
      //Received some other kind of event
      //Turn down this noise level, because it's super noisy
      log.debug(s"UNHANDLED -- Received ${x}")
  }
}