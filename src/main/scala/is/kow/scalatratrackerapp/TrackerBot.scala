package is.kow.scalatratrackerapp

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{Directives, Route}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.codahale.metrics.MetricRegistry
import is.kow.scalatratrackerapp.actors.MetricsActor.RequestMetrics
import is.kow.scalatratrackerapp.actors._
import is.kow.scalatratrackerapp.actors.persistence.PersistenceActor
import is.kow.scalatratrackerapp.actors.pivotal.PivotalRequestActor
import org.apache.http.HttpHost
import spray.json.PrettyPrinter

import scala.concurrent.Future

object TrackerBot extends App with Directives with SprayJsonSupport {
  implicit val system = ActorSystem("tracker-bot")
  implicit val executor = system.dispatcher
  implicit val materializer = ActorMaterializer()

  //Loading my config TODO: probably a better way to do this
  val config = AppConfig.config
  //Using the akka event logger should work better than other things.
  //It's dispatched on a thread, so it doesn't bog the system down
  val logger = Logging(system, getClass)

  //Singleton slackbot actor!
  val slackBotActor = system.actorOf(SlackBotActor.props, "slack-bot-actor")

  //Set up the proxy stuff, if we have a proxy
  val proxyOption: Option[HttpHost] = if (config.getString("https.proxyHost").nonEmpty) {
    //Set the proxy !
    Some(new HttpHost(config.getString("https.proxyHost"), config.getInt("https.proxyPort")))
  } else {
    None
  }

  //Start up the singleton actors we need
  logger.debug("Creating HTTP Actor!")
  val httpActor = system.actorOf(HttpRequestActor.props(proxyOption), "http-request-actor")
  //Introduce the PivotalRequest Actor to the Http Actor, so that it can be used
  system.actorOf(PivotalRequestActor.props(httpActor), "pivotal-request-actor")

  //TODO: I'm not sure the persistence actor really needs the slackbot actor.
  //I was trying to figure out how to hook up a database migration that can talk to slack, but I'm not sure how to do that.
  val persistenceActor = system.actorOf(PersistenceActor.props(slackBotActor, AppConfig.dbCreds), "persistence-actor")

  //a top level actor, so there's ever only one request to the data base at a time anyway?
  system.actorOf(ChannelProjectActor.props(persistenceActor), "channel-project-actor")

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
