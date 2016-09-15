package is.kow.scalatratrackerapp.actors


import akka.actor.{Actor, ActorContext, ActorLogging, Props}
import com.ullink.slack.simpleslackapi.SlackPersona.SlackPresence
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import com.ullink.slack.simpleslackapi.impl.SlackSessionFactory
import com.ullink.slack.simpleslackapi.listeners.SlackMessagePostedListener
import com.ullink.slack.simpleslackapi.{SlackPreparedMessage, SlackSession}
import is.kow.scalatratrackerapp.AppConfig
import is.kow.scalatratrackerapp.actors.commands.{TrackerPatternRegistrationActor, TrackerRegistrationCommandActor}

import scala.util.matching.Regex

/**
  * the actor to connect to slack, and hold open the websocket connection.
  * Translates messages from slack into other messages for other actors
  *
  */

object SlackBotActor {

  def props = Props[SlackBotActor]

  case class StoryDetailsRequest(slackMessagePosted: SlackMessagePosted, storyId: Long)

  case object Start

  case class SlackMessage(
                           token: Option[String] = None,
                           channel: String,
                           text: Option[String] = None,
                           slackPreparedMessage: Option[SlackPreparedMessage] = None,
                           asUser: Option[Boolean] = None
                         )

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

  //This is mutable, because we're going to update it in the actor
  var regexRegistrations: List[RegisterRegex] = List.empty[RegisterRegex]
  var commandRegistrations: List[RegisterCommand] = List.empty[RegisterCommand]

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

      //Create all the actors for commands right here they will send messages to this guy to fire up
      // This way if the slack connection dies, all of the things get restarted, they're transient
      List(
        TrackerPatternRegistrationActor.props,
        TrackerRegistrationCommandActor.props
      ).foreach { props =>
        val actor = context.actorOf(props)
        actor ! Start
      }

      context.become(readyForService)
  }

  def readyForService: Actor.Receive = {
    case s: SlackMessage =>
      //Send the message to the client!
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
        log.error(s"No message payload to send: ${s}")
      }

    case registerRegex: RegisterRegex =>
      //Someone's asking to register a regular expression for us to process, caaaaan do
      regexRegistrations = registerRegex :: regexRegistrations
    //TODO: some day confirm registration?

    case registerCommand: RegisterCommand =>
      commandRegistrations = registerCommand :: commandRegistrations

      //This is a message coming from slack, either from us, or to us, or to someone else.
    case smp: SlackMessagePosted => {
      val botPersona = session.sessionPersona()
      val mentioned = smp.getMessageContent.contains(s"<@${botPersona.getId}>")
      log.debug(s"Looking for a mention of me: <@${botPersona.getId}> -> ${mentioned}")
      log.debug(s"MESSAGE RECEIVED: ${smp.getMessageContent}")

      //need to filter out messages the bot itself sent, because we don't want those
      if (smp.getSender.getId != botPersona.getId) {
        log.debug("the message didn't come from me!")
        //TODO: also need to create a help actor for when people ask about help

        //for each regex registration, call the function, which might result in a message to send to some actor
        //TODO: should probably make this parallel at one point, rather than serial, this is wasting CPU
        //TODO: where does typing go in ?
        regexRegistrations.foreach { r =>
          r.messageFunction(r.regex, smp).foreach { message =>
            //If we matched a regex, we should send typing
            session.sendTyping(smp.getChannel)
            context.actorOf(r.props) ! message
          }
        }

        //For now handle another command but only when I'm mentioned
        if (mentioned) {
          //It's a message to me!
          //NOTE: for some reason the IDs come back in <> and I don't know why
          val mentionPrefix = s"\\s*<@${botPersona.getId}>[:,]?\\s*"

          commandRegistrations.foreach { c =>
            //compile the proper regex
            val commandRegex = s"$mentionPrefix${c.regex}".r
            log.debug(s"Working on $c")
            c.messageFunction(commandRegex, smp).foreach { message =>
              log.debug("got a message to process")
              session.sendTyping(smp.getChannel)
              context.actorOf(c.props) ! message
            }
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