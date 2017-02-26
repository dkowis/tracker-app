import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import is.kow.scalatratrackerapp.actors.pivotal.PivotalRequestActor
import is.kow.scalatratrackerapp.actors.{ChannelProjectActor, SlackBotActor}
import is.kow.scalatratrackerapp.{AppConfig, Persistence}

object TrackerBot extends App {
  implicit val system = ActorSystem()
  implicit val executor = system.dispatcher
  implicit val materializer = ActorMaterializer()

  //Loading my config TODO: probably a better way to do this
  val config = AppConfig.config
  val logger = Logging(system, getClass)


  //TODO: this route is really only for the cloud foundry health check
  // I need to get some decent routes in there for something useful. Metrics, would be the best
  val route: Route =
  path("") {
    get {
      complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, "Alive!"))
    }
  }


  //TODO: I could do things with return to quit or something!

  //Fire up the things I need
  //TODO: re-evaluate my entire life
  val todoWorthless = Persistence.db

  //Start up the singleton actors we need
  system.actorOf(PivotalRequestActor.props, "pivotal-request-actor")
  system.actorOf(ChannelProjectActor.props, "channel-project-actor")

  system.actorOf(SlackBotActor.props, "slack-bot-actor")

  // `route` will be implicitly converted to `Flow` using `RouteResult.route2HandlerFlow`
  //Sometimes the magic doesn't always work, good to know this ^^
  //RouteResult.route2HandlerFlow
  Http().bindAndHandle(route, config.getString("http.interface"), config.getInt("http.port"))
}
