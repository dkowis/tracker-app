package actors

import java.util.concurrent.TimeUnit
import javax.inject.Inject

import akka.actor.{Actor, ActorLogging}
import com.google.common.cache.{Cache, CacheBuilder}
import play.api.libs.json.{JsError, JsSuccess, Json}
import play.api.{Configuration, Logger}
import play.api.libs.ws.WSClient
import services.{PivotalJsonImplicits, PivotalLabel, PivotalStory}

object RequestActor {

  case class StoryDetails(projectId: Long, storyId: Long)

  case class Labels(projectId: Long)

}

class RequestActor @Inject()(config: Configuration, ws: WSClient) extends Actor with ActorLogging {

  import RequestActor._

  implicit val executionContext = context.dispatcher

  val labelCache: Cache[String, List[PivotalLabel]] = CacheBuilder.
    newBuilder().
    expireAfterWrite(1, TimeUnit.MINUTES).
    build[String, List[PivotalLabel]]()

  val labelCache2: Cache[String, PivotalLabel] = CacheBuilder.
    newBuilder().
    expireAfterWrite(30, TimeUnit.SECONDS).
    build[String, PivotalLabel]()



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
      val storyUrl = baseUrl + s"/projects/${storyDetails.projectId}/stories/${storyDetails.storyId}"
      ws.url(storyUrl).withHeaders("X-TrackerToken" -> trackerToken).get().map { response =>
        import services.PivotalJsonImplicits._
        response.json.validate[PivotalStory] match {
          case s: JsSuccess[PivotalStory] =>
            //Give the sender back the Pivotal Story
            sender() ! s.get
          case e: JsError =>
            log.error(s"Unable to parse response from Pivotal: ${JsError.toJson(e)}")
            sender() ! e
        }
      }
    }
    case labels: Labels => {
      Option(labelCache.getIfPresent(labels.projectId.toString)).map { labelList =>
        sender() ! labelList
      } getOrElse {
        val labelUrl = baseUrl + s"/projects/${labels.projectId}/labels"
        ws.url(labelUrl).withHeaders("X-TrackerToken" -> trackerToken).get().map { response =>
          import PivotalJsonImplicits._
          response.json.validate[List[PivotalLabel]] match {
            case s: JsSuccess[List[PivotalLabel]] =>
              labelCache.put(labels.projectId.toString, s.get)
              sender() ! s.get
            case e: JsError =>
              log.error(s"Unable to parse response from Pivotal: ${JsError.toJson(e)}")
              sender() ! e
          }
        }
      }
    }
  }
}
