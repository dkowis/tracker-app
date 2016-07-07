package is.kow.scalatratrackerapp.actors

import akka.actor.{Actor, ActorLogging, Props}
import is.kow.scalatratrackerapp.json.{SlackJsonImplicits, SlackMessage, SlackResponse}
import is.kow.scalatratrackerapp.{AppConfig, MyWSClient}
import play.api.libs.json.{JsError, JsSuccess, Json}

object SlackRequestActor {
  def props = Props[SlackRequestActor]
}

class SlackRequestActor extends Actor with ActorLogging  {

  implicit val executionContext = context.dispatcher

  val config = AppConfig.config
  val ws = MyWSClient.wsClient

  val baseUrl = "https://slack.com/api"

  val slackToken = config.getString("slack.token")

  def receive = {
    case slackMessage: SlackMessage => {
      log.debug("Got a request to send a message to slack!")
      val sendingActor = sender()
      val storyUrl = baseUrl + "/chat.postMessage"
      import SlackJsonImplicits._

      val postMap:Map[String, Seq[String]] = Map(
        "token" -> Seq(slackMessage.token.get.toString),
        "channel" -> Seq(slackMessage.channel.toString),
        "attachments" -> Seq(Json.stringify(Json.toJson(slackMessage.attachments.get))),
        "as_user" -> Seq("true")
      )

      //ONE DOESN"T POST TO SLACK IN JSON!!!!11ONE
      ws.url(storyUrl).post(postMap).map { response =>
        log.debug(s"SLACK RESPONSE: ${Json.prettyPrint(response.json)}")

        response.json.validate[SlackResponse] match {
          case s: JsSuccess[SlackResponse] =>
          //TODO: do something about it
            //Should always check the "ok" field and log it or something...
          case e: JsError =>
            log.error(s"Unable to parse JSON from slack: ${JsError.toJson(e)}")
        }
      }
    }
  }
}

