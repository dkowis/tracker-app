package is.kow.scalatratrackerapp.actors.pivotal

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, Props}
import com.google.common.cache.{Cache, CacheBuilder}
import is.kow.scalatratrackerapp.{AppConfig, MyWSClient}
import play.api.libs.json.{JsError, JsSuccess}

object PivotalRequestActor {

  def props = Props[PivotalRequestActor]

  case class StoryDetails(projectId: Long, storyId: Long)

  case class Labels(projectId: Long)

  //TODO: implement a chore creation that puts it in the current iteration too
  case class CreateChore(projectId: Long, name: String, requestedById: Long, comments: String)

  //https://www.pivotaltracker.com/help/api/rest/v5#projects_project_id_memberships_get
  case class ListMembers(projectId: Long)
}

//TODO: find a way to handle errors better, so that I can report back on pivotal's errors

class PivotalRequestActor extends Actor with ActorLogging  {

  import PivotalRequestActor._

  implicit val executionContext = context.dispatcher

  val ws = MyWSClient.wsClient
  val config = AppConfig.config

  val labelCache: Cache[String, List[PivotalLabel]] = CacheBuilder.
    newBuilder().
    expireAfterWrite(1, TimeUnit.MINUTES).
    build[String, List[PivotalLabel]]()

  //Had to use strings, because of the java generics things
  val memberCache: Cache[String, List[PivotalPerson]] = CacheBuilder.
    newBuilder().
    expireAfterWrite(1, TimeUnit.MINUTES). //TODO: this could probably be a whole lot longer
    build[String, List[PivotalPerson]]()

  val baseUrl = config.getString("tracker.base")

  val trackerToken = config.getString("tracker.token")

  //the whole purpose of this class is to marshall json!
  import PivotalJsonImplicits._

  def receive = {
    //Read-only task
    case storyDetails: StoryDetails => {
      log.debug("Got a request for story details!")
      val sendingActor = sender()
      val storyUrl = baseUrl + s"/projects/${storyDetails.projectId}/stories/${storyDetails.storyId}"
      ws.url(storyUrl).withHeaders("X-TrackerToken" -> trackerToken).get().map { response =>

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

    //Read-only operation
    case labels: Labels => {
      val sendingActor = sender()
      Option(labelCache.getIfPresent(labels.projectId.toString)).map { labelList =>
        log.debug(s"My actor ref to reply is: ${sender().toString}")
        sendingActor ! labelList
      } getOrElse {
        val labelUrl = baseUrl + s"/projects/${labels.projectId}/labels"
        ws.url(labelUrl).withHeaders("X-TrackerToken" -> trackerToken).get().map { response =>
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

    case listMembers: ListMembers =>
      val sendingActor = sender()
      Option(memberCache.getIfPresent(listMembers.projectId)).map { membersList =>
        sendingActor ! membersList
      } getOrElse {
        val membersUrl = s"$baseUrl/projects/${listMembers.projectId}/memberships"
        ws.url(membersUrl).withHeaders("X-TrackerToken" -> trackerToken).get().map { response =>
          response.json.validate[List[PivotalMember]] match {
            case s: JsSuccess[List[PivotalMember]] =>
              val persons = s.get.map {pm =>
                pm.person
              }
              memberCache.put(listMembers.projectId.toString, persons)
              sendingActor ! s.get
            case e: JsError =>
              log.error(s"Unable to parse response from Pivotal: ${JsError.toJson(e)}")
              sendingActor ! e
          }
        }
      }
  }
}
