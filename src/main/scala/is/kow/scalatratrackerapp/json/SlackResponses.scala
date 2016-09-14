package is.kow.scalatratrackerapp.json

import com.github.tototoshi.play.json.JsonNaming
import com.ullink.slack.simpleslackapi.{SlackChannel, SlackPreparedMessage}

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
                         slackPreparedMessage: Option[SlackPreparedMessage] = None,
                         asUser: Option[Boolean] = None
                       )

case class SlackResponse(
                          ok: Boolean,
                          error: Option[String],
                          warning: Option[String],
                          ts: Option[String],
                          channel: Option[String]
                        )
