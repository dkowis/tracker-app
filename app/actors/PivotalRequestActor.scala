package actors

import java.util.concurrent.TimeUnit
import javax.inject.Inject

import akka.actor.{Actor, ActorLogging}
import com.google.common.cache.{Cache, CacheBuilder}
import play.api.Configuration
import play.api.libs.json.{JsError, JsSuccess}
import play.api.libs.ws.WSClient
import services.{PivotalLabel, PivotalStory}

object PivotalRequestActor {

  case class StoryDetails(projectId: Long, storyId: Long)

  case class Labels(projectId: Long)

}

class PivotalRequestActor @Inject()(config: Configuration, ws: WSClient) extends Actor with ActorLogging {

  import PivotalRequestActor._

  implicit val executionContext = context.dispatcher

  val labelCache: Cache[String, List[PivotalLabel]] = CacheBuilder.
    newBuilder().
    expireAfterWrite(1, TimeUnit.MINUTES).
    build[String, List[PivotalLabel]]()

  val baseUrl = config.getString("tracker.base").getOrElse {
    log.error("Unable to find tracker.base configuration!")
    "nope"
  }

  val trackerToken = config.getString("tracker.token").getOrElse {
    log.error("Unable to find tracker.token configuration")
    "nope"
  }

  def receive = {
    case storyDetails: StoryDetails => {
      log.debug("Got a request for story details!")
      val sendingActor = sender()
      val storyUrl = baseUrl + s"/projects/${storyDetails.projectId}/stories/${storyDetails.storyId}"
      ws.url(storyUrl).withHeaders("X-TrackerToken" -> trackerToken).get().map { response =>
        import services.PivotalJsonImplicits._
        response.json.validate[PivotalStory] match {
          case s: JsSuccess[PivotalStory] =>
            //Give the sender back the Pivotal Story
            sendingActor ! s.get
          case e: JsError =>
            log.error(s"Unable to parse response from Pivotal: ${JsError.toJson(e)}")
            sendingActor ! e
        }
      }
    }
    case labels: Labels => {
      val sendingActor = sender()
      Option(labelCache.getIfPresent(labels.projectId.toString)).map { labelList =>
        log.debug(s"My actor ref to reply is: ${sender().toString}")
        sendingActor ! labelList
      } getOrElse {
        val labelUrl = baseUrl + s"/projects/${labels.projectId}/labels"
        ws.url(labelUrl).withHeaders("X-TrackerToken" -> trackerToken).get().map { response =>
          import services.PivotalJsonImplicits._

          response.json.validate[List[PivotalLabel]] match {
            case s: JsSuccess[List[PivotalLabel]] =>
              labelCache.put(labels.projectId.toString, s.get)
              sendingActor ! s.get
            case e: JsError =>
              log.error(s"Unable to parse response from Pivotal: ${JsError.toJson(e)}")
              sendingActor ! e
          }
        }
      }
    }
  }
}
