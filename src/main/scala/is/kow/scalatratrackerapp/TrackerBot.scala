package is.kow.scalatratrackerapp

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directives, Route}
import akka.stream.ActorMaterializer
import akka.pattern.ask
import akka.util.Timeout
import com.codahale.metrics.{Metric, MetricRegistry}
import is.kow.scalatratrackerapp.actors.MetricsActor.RequestMetrics
import is.kow.scalatratrackerapp.actors.pivotal.PivotalRequestActor
import is.kow.scalatratrackerapp.actors.{ChannelProjectActor, MetricRegistryJsonProtocol, MetricsActor, SlackBotActor}
import spray.json.PrettyPrinter

import scala.concurrent.Future

object TrackerBot extends App with Directives with SprayJsonSupport {
  implicit val system = ActorSystem()
  implicit val executor = system.dispatcher
  implicit val materializer = ActorMaterializer()

  //Loading my config TODO: probably a better way to do this
  val config = AppConfig.config
  val logger = Logging(system, getClass)
  //val logger = LoggerFactory.getLogger(TrackerBot.getClass)

  system.actorOf(SlackBotActor.props, "slack-bot-actor")

  //Fire up the things I need
  //TODO: re-evaluate my entire life
  val todoWorthless = Persistence.db

  //Start up the singleton actors we need
  system.actorOf(PivotalRequestActor.props, "pivotal-request-actor")
  system.actorOf(ChannelProjectActor.props, "channel-project-actor")

  val metricsActor = system.actorOf(MetricsActor.props, "metrics-actor")


  //TODO: this route is really only for the cloud foundry health check
  // I need to get some decent routes in there for something useful. Metrics, would be the best

  val route: Route =
    pathSingleSlash {
      get {
        complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, "Alive!"))
      }
    } ~
      path("metrics") {
        get {
          import scala.concurrent.duration._

          implicit val timeout: Timeout = 5.seconds
          //AHA, THIS IS THE MAGICAL CONVERSION
          implicit val metricsFormatter = MetricRegistryJsonProtocol.MetricRegistryJsonFormat
          implicit val printer = PrettyPrinter

          val metrics: Future[MetricRegistry] = (metricsActor ? RequestMetrics).mapTo[MetricRegistry]

          complete(metrics)
        }
      }


  //TODO: I could do things with return to quit or something!


  // `route` will be implicitly converted to `Flow` using `RouteResult.route2HandlerFlow`
  //Sometimes the magic doesn't always work, good to know this ^^
  //RouteResult.route2HandlerFlow
  Http().bindAndHandle(route, config.getString("http.interface"), config.getInt("http.port"))
  logger.info("I'm in ur HTTP servin ur things")
}
