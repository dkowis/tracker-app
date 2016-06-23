package actors

import javax.inject.Inject

import actors.PivotalRequestActor.StoryDetails
import akka.actor.{Actor, ActorLogging}
import play.api.Configuration
import services.SlackMessage
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

  val client = SlackRtmClient(token)
  client.addEventListener(self)

  def receive = {
    case s: SlackMessage =>
      //Send the message to the client!

      //TODO: to send pretty messages: https://api.slack.com/methods/chat.postMessage
      val tokenized = s.copy(token = Some(token))
      context.actorSelection("/user/slack-request-actor") ! tokenized

    case m: Message => {
      //ZOMG A MESSAGE, lets send
      val mentions = SlackUtil.extractMentionedIds(m.text)

      //TODO: build commands and stuff in here.
      //TODO: this is too much to live in here, need to build another actor
      val trackerStoryPattern = ".*T(\\d+).*".r
      for {
        trackerStoryPattern(storyId) <- trackerStoryPattern findFirstIn m.text
      } yield {
        //create an actor for story details, ship it
        context.actorOf(StoryDetailActor.props) ! StoryDetailsRequest(m.channel, StoryDetails(projectId, storyId.toLong))
      }

      //Also look for a tracker URL and expand up on that
      val trackerUrlPattern =  ".*https://www.pivotaltracker.com/story/show/(\\d+).*".r
      for {
        trackerUrlPattern(storyId) <- trackerUrlPattern findFirstIn m.text
      } yield {
        //create an actor for story details, ship it
        context.actorOf(StoryDetailActor.props) ! StoryDetailsRequest(m.channel, StoryDetails(projectId, storyId.toLong))
      }

    }
  }
}
