package is.kow.scalatratrackerapp.actors.pivotal

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, Props}
import com.google.common.cache.{Cache, CacheBuilder}
import is.kow.scalatratrackerapp.{AppConfig, MyWSClient}
import play.api.libs.json.{JsError, JsSuccess, Json}

object PivotalRequestActor {

  def props = Props[PivotalRequestActor]

  case class StoryDetails(projectId: Long, storyId: Long)

  case class Labels(projectId: Long)

  case class LabelsList(labels: List[PivotalLabel])

  //TODO: implement a chore creation that puts it in the current iteration too
  case class CreateChore(projectId: Long, name: String, assignToId: Option[Long], description: Option[String])

  case class ItemCreated(projectId: Long, itemId: Long)

  //https://www.pivotaltracker.com/help/api/rest/v5#projects_project_id_memberships_get
  case class ListMembers(projectId: Long)

  case class Members(members: List[PivotalPerson])

}

//TODO: find a way to handle errors better, so that I can report back on pivotal's errors

class PivotalRequestActor extends Actor with ActorLogging {

  import PivotalRequestActor._

  implicit val executionContext = context.dispatcher

  val ws = MyWSClient.wsClient
  val config = AppConfig.config

  val labelCache: Cache[String, LabelsList] = CacheBuilder.
    newBuilder().
    expireAfterWrite(1, TimeUnit.MINUTES).
    build[String, LabelsList]()

  //Had to use strings, because of the java generics things
  val memberCache: Cache[String, Members] = CacheBuilder.
    newBuilder().
    expireAfterWrite(1, TimeUnit.MINUTES). //TODO: this could probably be a whole lot longer
    build[String, Members]()

  val baseUrl = config.getString("tracker.base")

  val trackerToken = config.getString("tracker.token")

  //the whole purpose of this class is to marshall json!
  import PivotalResponseJsonImplicits._

  //import PivotalRequestJsonImplicits._

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
        sendingActor ! labelList //need to encapsulate it because erasure
      } getOrElse {
        val labelUrl = baseUrl + s"/projects/${labels.projectId}/labels"
        ws.url(labelUrl).withHeaders("X-TrackerToken" -> trackerToken).get().map { response =>
          response.json.validate[List[PivotalLabel]] match {
            case s: JsSuccess[List[PivotalLabel]] =>
              labelCache.put(labels.projectId.toString, LabelsList(s.get))
              sendingActor ! LabelsList(s.get)
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
              val persons = Members(s.get.map(pm => pm.person))
              memberCache.put(listMembers.projectId.toString, persons)
              sendingActor ! persons
            case e: JsError =>
              log.error(s"Unable to parse response from Pivotal: ${JsError.toJson(e)}")
              sendingActor ! e
          }
        }
      }

    case c: CreateChore =>
      val sendingActor = sender()
      val owners = c.assignToId.map { id =>
        List(id)
      } getOrElse {
        List.empty[Long]
      }

      import PivotalRequestJsonImplicits._
      val payload = PivotalStoryCreation(
        projectId = c.projectId,
        name = c.name,
        storyType = "chore",
        description = c.description,
        ownerIds = owners
      )

      //Post that payload to pivotal
      log.debug(s"Trying to post to $baseUrl/projects/${c.projectId}/stories")
      ws.url(s"$baseUrl/projects/${c.projectId}/stories")
        .withHeaders("X-TrackerToken" -> trackerToken)
        .post(Json.toJson(payload)).map { response =>
        //sent!
        log.debug(s"Response code from tracker: ${response.status}")
        log.debug(s"Response body: ${response.body}")
        response.json.validate[PivotalStory] match {
          case s: JsSuccess[PivotalStory] =>
            //Worked! got details
            sendingActor ! s.get
          case e: JsError =>
            log.error(s"Wasn't able to successfully create story, or I couldn't read their JSON: ${JsError.toJson(e)}")
            sendingActor ! e
        }
      }
  }
}
