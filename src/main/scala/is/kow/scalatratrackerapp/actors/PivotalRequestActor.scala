package is.kow.scalatratrackerapp.actors

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, Props}
import com.google.common.cache.{Cache, CacheBuilder}
import is.kow.scalatratrackerapp.{AppConfig, MyWSClient}
import is.kow.scalatratrackerapp.json.{PivotalJsonImplicits, PivotalLabel, PivotalStory}
import play.api.libs.json.{JsError, JsSuccess}

object PivotalRequestActor {

  def props = Props[PivotalRequestActor]

  case class StoryDetails(projectId: Long, storyId: Long)

  case class Labels(projectId: Long)

}

class PivotalRequestActor extends Actor with ActorLogging  {

  import PivotalRequestActor._

  implicit val executionContext = context.dispatcher

  val ws = MyWSClient.wsClient
  val config = AppConfig.config

  val labelCache: Cache[String, List[PivotalLabel]] = CacheBuilder.
    newBuilder().
    expireAfterWrite(1, TimeUnit.MINUTES).
    build[String, List[PivotalLabel]]()

  val baseUrl = config.getString("tracker.base")

  val trackerToken = config.getString("tracker.token")

  def receive = {
    case storyDetails: StoryDetails => {
      log.debug("Got a request for story details!")
      val sendingActor = sender()
      val storyUrl = baseUrl + s"/projects/${storyDetails.projectId}/stories/${storyDetails.storyId}"
      ws.url(storyUrl).withHeaders("X-TrackerToken" -> trackerToken).get().map { response =>
        import PivotalJsonImplicits._

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
          import PivotalJsonImplicits._

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
