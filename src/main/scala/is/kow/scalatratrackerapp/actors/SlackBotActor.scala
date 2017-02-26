package is.kow.scalatratrackerapp.actors


import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef, Props}
import com.ullink.slack.simpleslackapi.SlackPersona.SlackPresence
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import com.ullink.slack.simpleslackapi.impl.SlackSessionFactory
import com.ullink.slack.simpleslackapi.listeners.SlackMessagePostedListener
import com.ullink.slack.simpleslackapi.{SlackChannel, SlackPreparedMessage, SlackSession}
import is.kow.scalatratrackerapp.AppConfig
import is.kow.scalatratrackerapp.actors.commands.{QuickChoreCommandActor, TrackerRegistrationCommandActor, UnstartedChoreCommandActor}
import is.kow.scalatratrackerapp.actors.responders.TrackerPatternRegistrationActor

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

  case class StartTyping(channel: SlackChannel)

  case class StopTyping(channel: SlackChannel)

  case object KeepTyping

  case class FindUserById(userId: String)

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

class SlackBotActor extends Actor with ActorLogging {

  import SlackBotActor._

  val configuration = AppConfig.config

  val token = configuration.getString("slack.token")

  //Have to have some mutable state for the client, because it can time out and fail to start....
  var session: SlackSession = null //TODO: GASP IM USING A NULL

  var messageListeners: List[ActorRef] = List.empty[ActorRef]
  var commandListeners: List[ActorRef] = List.empty[ActorRef]

  val typingChannels: scala.collection.mutable.Map[String, Int] = mutable.Map.empty[String, Int]

  self ! Start

  //tell myself to start every time I'm created

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

      //Create all the actors for commands right here they will send messages to this guy to fire up
      // This way if the slack connection dies, all of the things get restarted, they're transient
      //TODO: this shouldn't be in here, I should create an actor to do this *every time* somehow
      List(
        TrackerPatternRegistrationActor.props,
        TrackerRegistrationCommandActor.props,
        QuickChoreCommandActor.props,
        UnstartedChoreCommandActor.props
      ).foreach { props =>
        val actor = context.actorOf(props)
        actor ! Start
      }

      //schedule the "Keep typing" message
      import context.dispatcher
      import scala.concurrent.duration._
      context.system.scheduler.scheduleOnce(1 second, self, KeepTyping)

      context.become(readyForService)
  }

  def readyForService: Actor.Receive = {
    case s: SlackMessage =>
      //Send the message to the client!
      //TODO this is extra super brittle! assumes always a channel
      val channel = Option(session.findChannelById(s.channel)).getOrElse {
        session.findChannelByName(s.channel)
      }
      //we're going to send a message or something, so stop typing (Could also just send this actor the message)
      stopTyping(channel.getId)

      log.debug(s"Attempting to send message: ${s}")
      if (s.slackPreparedMessage.isDefined) {
        session.sendMessage(channel, s.slackPreparedMessage.get)

      } else if (s.text.isDefined) {
        session.sendMessage(channel, s.text.get)
      } else {
        //TODO: neither was defined, and thats bad!
        log.error(s"No message payload to send: ${s}")
      }

    case f: FindUserById =>
      //Wrapped to return an option of the slack user, because it might not exist
      sender ! Option(session.findUserById(f.userId))

    case r: RegisterForMessages =>
      messageListeners = sender :: messageListeners

    case r: RegisterForCommands =>
      commandListeners = sender :: commandListeners
      sender ! CommandPrefix(s"\\s*<@${session.sessionPersona().getId}>[:,]?\\s*")

    case startTyping: StartTyping =>
      if (typingChannels.contains(startTyping.channel.getId)) {
        //increment it .. will this work
        typingChannels(startTyping.channel.getId) += 1
      } else {
        typingChannels(startTyping.channel.getId) = 1
      }
      session.sendTyping(startTyping.channel)


    case st: StopTyping =>
      stopTyping(st.channel.getId)

    case KeepTyping =>
      typingChannels.foreach { case (channelId, count) =>
        if (count > 0) {
          session.sendTyping(session.findChannelById(channelId))
        }
      }
      //schedule the "Keep typing" message again, so it keeps happening, once a second
      import context.dispatcher
      import scala.concurrent.duration._
      context.system.scheduler.scheduleOnce(1 second, self, KeepTyping)



    //This is a message coming from slack, either from us, or to us, or to someone else.
    case smp: SlackMessagePosted => {
      val botPersona = session.sessionPersona()
      val mentioned = smp.getMessageContent.contains(s"<@${botPersona.getId}>")
      log.debug(s"Looking for a mention of me: <@${botPersona.getId}> -> ${mentioned}")
      log.debug(s"MESSAGE RECEIVED: ${smp.getMessageContent}")

      log.debug(s"got a message from ${smp.getSender}");

      //need to filter out messages the bot itself sent, because we don't want those
      if (smp.getSender.getId != botPersona.getId) {
        log.debug("the message didn't come from me!")
        //TODO: also need to create a help actor for when people ask about help

        messageListeners.foreach { actor =>
          actor ! smp
        }

        //For now handle another command but only when I'm mentioned
        if (mentioned) {
          //It's a message to me! I got mentioned, not necessarily in the right order
          commandListeners.foreach { actor =>
            actor ! smp
          }
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