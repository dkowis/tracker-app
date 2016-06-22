package controllers

import javax.inject._

import akka.actor.ActorSystem
import play.api.libs.json.Json
import play.api.mvc._

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class HubotController @Inject()(system: ActorSystem) extends Controller {

  /**
   * Create an Action to render an HTML page with a welcome message.
   * The configuration in the `routes` file means that this method
   * will be called when the application receives a `GET` request with
   * a path of `/`.
   */
  def serviceBroker = Action {
    val json = Json.parse(getClass.getResourceAsStream("/hubotService.json"))

    Ok(json)
  }

}
