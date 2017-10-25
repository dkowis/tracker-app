package is.kow.scalatratrackerapp.actors


import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import com.typesafe.config.Config
import com.ullink.slack.simpleslackapi.SlackPersona.SlackPresence
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import com.ullink.slack.simpleslackapi.impl.SlackSessionFactory
import com.ullink.slack.simpleslackapi.listeners.SlackMessagePostedListener
import com.ullink.slack.simpleslackapi.{SlackChannel, SlackPreparedMessage, SlackSession}
import is.kow.scalatratrackerapp.AppConfig
import is.kow.scalatratrackerapp.actors.commands.{TrackerProjectsCommandActor, TrackerRegistrationCommandActor}
import is.kow.scalatratrackerapp.actors.responders.TrackerStoryPatternActor
import nl.grons.metrics.scala.{Counter, DefaultInstrumented, Timer}

import scala.collection.mutable
import scala.util.matching.Regex

/**
  * the actor to connect to slack, and hold open the websocket connection.
  * Translates messages from slack into other messages for other actors
  *
  */

object SlackBotActor {

  def props = Props[SlackBotActor]

  case object Start

  case class SlackTyping(channel: SlackChannel)

  case class FindUserById(userId: String)

  case class FindChannelByName(name: String)

  //used by the timed message thing

  case class SlackMessage(
                           channel: String,
                           text: Option[String] = None,
                           slackPreparedMessage: Option[SlackPreparedMessage] = None
                         )

  //Just a message that indicates that the sender wants to register for messages
  case class RegisterForMessages()

  case class RegisterForCommands()

  case class CommandPrefix(prefix: String)

  //This should be the registration command, with a function that will know how to process the regex and send a message
  // That code will be called by this actor, so don't leak things
  //TODO: can probably figure out a way to make it typed
  //TODO: this could be a pure string, and probably should be too
  case class RegisterRegex(regex: Regex, props: Props, messageFunction: (Regex, SlackMessagePosted) => Option[Any])

  //AnyVal, because it's a case class to send
  //The regex will get compiled after more goodies are added to it
  case class RegisterCommand(regex: String, props: Props, messageFunction: (Regex, SlackMessagePosted) => Option[Any])
}

class SlackBotActor extends Actor with ActorLogging with DefaultInstrumented {

  import SlackBotActor._

  private val configuration: Config = AppConfig.config

  private val token: String = configuration.getString("slack.token")

  //Have to have some mutable state for the client, because it can time out and fail to start....
  private var session: SlackSession = null //TODO: GASP IM USING A NULL

  private var messageListeners: List[ActorRef] = List.empty[ActorRef]
  private var commandListeners: List[ActorRef] = List.empty[ActorRef]

  private val typingChannels: scala.collection.mutable.Map[String, Int] = mutable.Map.empty[String, Int]

  private var commandPrefix: CommandPrefix = _

  //Metrics
  private val messagesSeen: Counter = metrics.counter("total_messages_seen")
  private val responseTimer: Timer = metrics.timer("slack_response_timer")

  //tell myself to start every time I'm created
  self ! Start


  //Post stop, make sure we disconnect from slack. I think this is part of the problem.
  override def postStop(): Unit = {
    super.postStop()

    Option(session).map { s =>
      log.info("Disconnecting from slack!")
      s.disconnect()
    }
  }

  def stopTyping(channelId: String): Unit = {
    if (typingChannels.contains(channelId)) {
      //decrement it .. will this work
      typingChannels(channelId) -= 1
      if (typingChannels(channelId) >= 0) {
        typingChannels.remove(channelId)
      }
    }
  }

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

      //Set the prefix once
      commandPrefix = CommandPrefix(s"\\s*<@${session.sessionPersona().getId}>[:,]?\\s*")

      context.become(readyForService)
  }

  def readyForService: Actor.Receive = {
    case s: SlackMessage =>
      //Send the message to the client!
      //TODO this is extra super brittle! assumes always a channel
      val channel = Option(session.findChannelById(s.channel)).getOrElse {
        session.findChannelByName(s.channel)
      }
      log.debug(s"Attempting to send message: ${s}")

      responseTimer.time {
        if (s.slackPreparedMessage.isDefined) {
          session.sendMessage(channel, s.slackPreparedMessage.get)
        } else if (s.text.isDefined) {
          session.sendMessage(channel, s.text.get)
        } else {
          //TODO: neither was defined, and thats bad!
          log.error(s"No message payload to send: ${s}")
        }
      }

    case f: FindUserById =>
      //Wrapped to return an option of the slack user, because it might not exist
      sender ! Option(session.findUserById(f.userId))

    case f: FindChannelByName =>
      sender ! Option(session.findChannelByName(f.name))

    //If we get a typing message, just emit it, simple
    case SlackTyping(channel) =>
      session.sendTyping(channel)

    //This is a message coming from slack, either from us, or to us, or to someone else.
    case smp: SlackMessagePosted => {
      messagesSeen.inc()
      val botPersona = session.sessionPersona()
      val mentioned = smp.getMessageContent.contains(s"<@${botPersona.getId}>")
      log.debug(s"Looking for a mention of me: <@${botPersona.getId}> -> ${mentioned}")
      log.debug(s"MESSAGE RECEIVED: ${smp.getMessageContent}")

      log.debug(s"got a message from ${smp.getSender}");

      //need to filter out messages the bot itself sent, because we don't want those
      if (smp.getSender.getId != botPersona.getId) {
        log.debug("the message didn't come from me!")
        //TODO: also need to create a help actor for when people ask about help

        //Create the actor and send it directly
        context.actorOf(TrackerStoryPatternActor.props) ! smp

        //For now handle another command but only when I'm mentioned
        if (mentioned) {
          context.actorOf(TrackerRegistrationCommandActor.props(commandPrefix)) ! smp
          //Disabling until it's working again the JSON API changed
          //context.actorOf(QuickChoreCommandActor.props(commandPrefix)) ! smp
          context.actorOf(TrackerProjectsCommandActor.props(commandPrefix)) ! smp
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