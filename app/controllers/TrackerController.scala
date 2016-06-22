package controllers

import javax.inject._

import play.api.Configuration
import play.api.libs.json.{JsError, JsSuccess, Json}
import play.api.mvc._
import services.{IncomingMessage, Pivotal, SlackMessage}

import scala.concurrent.{ExecutionContext, Future}

/**
  * This controller creates an `Action` to handle HTTP requests to the
  * application's home page.
  */
@Singleton
class TrackerController @Inject()(config:Configuration, pivotal: Pivotal)(implicit context: ExecutionContext) extends Controller {

  /**
    * This needs to be an asynchronous action to go ask the tracker API for something
    *
    * @return
    */
  def itemDetails = Action.async(parse.json) { request =>
    import services.HubotRequestImplicits._

    request.body.validate[IncomingMessage] match {
      case s: JsSuccess[IncomingMessage] => {
        //call pivotal
        //return that response!
        val storyId = s.get.matches.head.toLong
        //TODO: assuming my config exists, shame on me
        val result = pivotal.storyDetails(config.getLong("project_id").get, storyId)
        val what:Future[Result] = result.map { futureResult =>
          //This *should* work
          futureResult.map { actualResult =>
            import services.SlackJsonImplicits._

            //Wrap the actual result in a slack message
            val slackMessage = SlackMessage(
              attachments = List(actualResult),
              channel = s.get.channel
            )
            Ok(Json.toJson(slackMessage))
          }
        } getOrElse {
          Future(NotFound("Not found"))
        }
        what
      }
      case e: JsError => {
        Future(BadRequest(JsError.toJson(e)))
      }
    }
  }

}
