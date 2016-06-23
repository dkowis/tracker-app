package actors

import javax.inject.Inject

import akka.actor.{Actor, ActorLogging}
import play.api.Configuration
import play.api.libs.json.{JsError, JsSuccess, Json}
import play.api.libs.ws.WSClient
import services.{SlackMessage, SlackResponse}

object SlackRequestActor {
  //TODO: using the things from services.SlackResponses
}

class SlackRequestActor @Inject()(config: Configuration, ws: WSClient) extends Actor with ActorLogging {

  implicit val executionContext = context.dispatcher

  val baseUrl = "https://slack.com/api"

  val slackToken = config.getString("slack.token").getOrElse {
    log.error("Unable to find slack.token configuration")
    "nope"
  }

  def receive = {
    case slackMessage: SlackMessage => {
      log.debug("Got a request to send a message to slack!")
      val sendingActor = sender()
      val storyUrl = baseUrl + "/chat.postMessage"
      import services.SlackJsonImplicits._

      val postMap:Map[String, Seq[String]] = Map(
        "token" -> Seq(slackMessage.token.get.toString),
        "channel" -> Seq(slackMessage.channel.toString),
        "attachments" -> Seq(Json.stringify(Json.toJson(slackMessage.attachments.get))),
        "as_user" -> Seq("true")
      )

      log.debug(s"Posting to slack: ${postMap}")

      //ONE DOESN"T POST TO SLACK IN JSON!!!!11ONE
      ws.url(storyUrl).post(postMap).map { response =>
        log.debug(s"SLACK RESPONSE: ${Json.prettyPrint(response.json)}")

        response.json.validate[SlackResponse] match {
          case s: JsSuccess[SlackResponse] =>
          //TODO: do something about it
          case e: JsError =>
            log.error(s"Unable to parse JSON from slack: ${JsError.toJson(e)}")
        }
      }
    }
  }
}
