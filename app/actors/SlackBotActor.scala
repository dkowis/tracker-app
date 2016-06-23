package actors

import javax.inject.Inject

import actors.RequestActor.StoryDetails
import akka.actor.{Actor, ActorLogging}
import play.api.Configuration
import play.api.libs.json.Json
import services.{SlackAttachment, SlackMessage}
import slack.SlackUtil
import slack.rtm.SlackRtmClient

/**
  * the actor to connect to slack, and hold open the websocket connection.
  * Translates messages from slack into other messages for other actors
  *
  */

object SlackBotActor {

  case class StoryDetailsRequest(channel: String, storyDetails: StoryDetails)

}

class SlackBotActor @Inject()(configuration: Configuration) extends Actor with ActorLogging{

  import SlackBotActor._
  import slack.models._

  val token = configuration.getString("slack.token").getOrElse("NOT_VALID")
  //TODO: hacking in the project id, because lame
  val projectId: Long = configuration.getLong("project_id").getOrElse(0l)

  //TODO: I think this is okay
  val client = SlackRtmClient(token)
  client.addEventListener(self)

  def receive = {
    case s: SlackMessage =>
      //Send the message to the client!
      import services.SlackJsonImplicits._
      //When I get some slack message, JSON-ify it and ship it
      //TODO: add a sendRawMessage API so I can send my own JSON for prettier messages
      client.sendMessage(s.channel, Json.stringify(Json.toJson(s)))

    case m: Message => {
      //ZOMG A MESSAGE, lets send
      val mentions = SlackUtil.extractMentionedIds(m.text)

      //TODO: build commands and stuff in here.

      if (mentions.contains(client.state.self.id)) {
        client.sendMessage(m.channel, s"Y HELO THAR <@${m.user}>")
      }

      val trackerStoryPattern = ".*T(\\d+).*".r

      for {
        trackerStoryPattern(storyId) <- trackerStoryPattern findFirstIn m.text
      } yield {
        //create an actor for story details, ship it
        context.actorOf(StoryDetailActor.props) ! StoryDetailsRequest(m.channel, StoryDetails(projectId, storyId.toLong))
      }
    }
  }
}
