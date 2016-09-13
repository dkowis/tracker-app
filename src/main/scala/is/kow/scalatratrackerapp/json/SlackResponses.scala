package is.kow.scalatratrackerapp.json

import com.github.tototoshi.play.json.JsonNaming
import com.ullink.slack.simpleslackapi.SlackChannel

case class SlackField(title: String,
                      value: String,
                      short: Boolean
                     )

case class SlackAttachment(
                            fallback: String,
                            title: String,
                            title_link: Option[String] = None,
                            text: String,
                            fields: Option[List[SlackField]] = None,
                            footer: Option[String] = None,
                            ts: Option[Long] = None,
                            footerIcon: Option[String] = None
                          )

/**
  * Used to send a slack message over the web API:
  * https://api.slack.com/methods/chat.postMessage
  * //TODO: this isn't quite the right way to do it
  * Doesn't work over the RTM api
  */
case class SlackMessage(
                         token: Option[String] = None,
                         channel: String,
                         text: Option[String] = None,
                         attachments: Option[List[SlackAttachment]] = None,
                         asUser: Option[Boolean] = None
                       )

case class SlackResponse(
                          ok: Boolean,
                          error: Option[String],
                          warning: Option[String],
                          ts: Option[String],
                          channel: Option[String]
                        )

object SlackJsonImplicits {

  import play.api.libs.json._

  //Oh god yes: https://www.playframework.com/documentation/2.5.x/ScalaJsonAutomated
  implicit val slackFieldWrites = JsonNaming.snakecase(Json.writes[SlackField])
  implicit val slackAttachmentWrites = JsonNaming.snakecase(Json.writes[SlackAttachment])
  implicit val slackMessageWrites = JsonNaming.snakecase(Json.writes[SlackMessage])

  //Reader for the slack response
  implicit val slackFieldReads = JsonNaming.snakecase(Json.reads[SlackField])
  implicit val slackAttachmentReads = JsonNaming.snakecase(Json.reads[SlackAttachment])
  implicit val slackMessageReads = JsonNaming.snakecase(Json.reads[SlackMessage])
  implicit val slackResponseReads = JsonNaming.snakecase(Json.reads[SlackResponse])
}