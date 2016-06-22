package services

import com.github.tototoshi.play.json.JsonNaming

case class SlackUser(
                      name: String,
                      realName: String,
                      emailAddress: String
                    )

case class IncomingMessage(
                            service: String,
                            pattern: String,
                            compiledPattern: String,
                            url: String,
                            matches: List[String],
                            user: SlackUser,
                            text: String,
                            robot: String,
                            channel: String
                          )


object HubotRequestImplicits {
  import play.api.libs.json._

  implicit val slackUserReads = JsonNaming.snakecase(Json.reads[SlackUser])
  implicit val incomingMessageReads = JsonNaming.snakecase(Json.reads[IncomingMessage])
}