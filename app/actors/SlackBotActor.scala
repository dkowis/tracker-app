package actors

import javax.inject.Inject

import akka.actor.{Actor, Props}
import play.api.Configuration
import slack.SlackUtil
import slack.rtm.SlackRtmClient

/**
  * the actor to connect to slack, and hold open the websocket connection.
  * Translates messages from slack into other messages for other actors
  *
  */

object SlackBotActor {
  def props = Props[SlackBotActor]
}

class SlackBotActor @Inject()(config: Configuration) extends Actor {
  import slack.models._

  val token = config.getString("slack.token").getOrElse("NOT_VALID")
  //TODO: I think this is okay
  val client = SlackRtmClient(token)
  client.addEventListener(self)

  def receive = {
    case m:Message => {
      //ZOMG A MESSAGE, lets send
      val mentions = SlackUtil.extractMentionedIds(m.text)

      if(mentions.contains(client.state.self.id)) {
        client.sendMessage(m.channel, s"Y HELO THAR <@${m.user}>");
      }
    }
  }
}
