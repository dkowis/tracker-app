package services

import com.github.tototoshi.play.json.JsonNaming

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

//TODO: probably more stuff
case class SlackMessage(
                         attachments: List[SlackAttachment],
                         channel: String
                       )

object SlackJsonImplicits {

  import play.api.libs.json._

  //Oh god yes: https://www.playframework.com/documentation/2.5.x/ScalaJsonAutomated
  implicit val slackFieldWrites = JsonNaming.snakecase(Json.writes[SlackField])
  implicit val slackAttachmentWrites = JsonNaming.snakecase(Json.writes[SlackAttachment])
  implicit val slackMessageWrites = JsonNaming.snakecase(Json.writes[SlackMessage])
}