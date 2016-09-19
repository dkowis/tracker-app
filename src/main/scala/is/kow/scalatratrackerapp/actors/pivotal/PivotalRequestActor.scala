package is.kow.scalatratrackerapp.actors.pivotal

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, Props}
import com.google.common.cache.{Cache, CacheBuilder}
import is.kow.scalatratrackerapp.AppConfig
import org.apache.http.client.entity.EntityBuilder
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.entity.ContentType
import org.apache.http.impl.nio.client.HttpAsyncClients
import org.apache.http.{HttpHost, HttpResponse}
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

class PivotalRequestActor extends Actor with ActorLogging {

  import PivotalRequestActor._

  implicit val executionContext = context.dispatcher

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
  val httpClient = {
    val builder = HttpAsyncClients.custom()
    if (config.getString("https.proxyHost").nonEmpty) {
      //Set the proxy !
      val proxy = new HttpHost(config.getString("https.proxyHost"), config.getInt("https.proxyPort"))
      builder.setProxy(proxy).build()
    }
    builder.build()
  }

  //Don't forget to start the httpclient
  httpClient.start()

  //TODO: at some point shut it off


  def receive = {
    //Read-only task
    case storyDetails: StoryDetails => {
      log.debug("Got a request for story details!")
      val storyUrl = baseUrl + s"/projects/${storyDetails.projectId}/stories/${storyDetails.storyId}"

      val request = new HttpGet(storyUrl)
      request.addHeader("X-TrackerToken", trackerToken)
      handleResponse(httpClient.execute(request, null).get()) { resp =>
        //This is the 2XX path
        Json.parse(resp.getEntity.getContent).validate[PivotalStory] match {
          case s: JsSuccess[PivotalStory] =>
            //Give the sender back the Pivotal Story
            sender() ! s.get
          case e: JsError =>
            //TODO: this should send back some other kind of error
            log.error(s"Unable to parse response from Pivotal: ${JsError.toJson(e)}")
            sender() ! e

        }
      }
    }

    //Read-only operation
    case labels: Labels => {
      Option(labelCache.getIfPresent(labels.projectId.toString)).map { labelList =>
        log.debug(s"My actor ref to reply is: ${sender().toString}")
        sender() ! labelList //need to encapsulate it because erasure
      } getOrElse {
        val labelUrl = baseUrl + s"/projects/${labels.projectId}/labels"
        val request = new HttpGet(labelUrl)
        request.addHeader("X-TrackerToken", trackerToken)
        request.addHeader("X-TrackerToken", trackerToken)
        handleResponse(httpClient.execute(request, null).get()) { resp =>
          Json.parse(resp.getEntity.getContent).validate[List[PivotalLabel]] match {
            case s: JsSuccess[List[PivotalLabel]] =>
              labelCache.put(labels.projectId.toString, LabelsList(s.get))
              sender() ! LabelsList(s.get)
            case e: JsError =>
              log.error(s"Unable to parse response from Pivotal: ${JsError.toJson(e)}")
              sender() ! e
          }
        }
      }
    }

    case listMembers: ListMembers =>
      Option(memberCache.getIfPresent(listMembers.projectId)).map { membersList =>
        sender() ! membersList
      } getOrElse {
        val membersUrl = s"$baseUrl/projects/${listMembers.projectId}/memberships"
        val request = new HttpGet(membersUrl)
        request.addHeader("X-TrackerToken", trackerToken)
        handleResponse(httpClient.execute(request, null).get()) { response =>
          Json.parse(response.getEntity.getContent).validate[List[PivotalMember]] match {
            case s: JsSuccess[List[PivotalMember]] =>
              val persons = Members(s.get.map(pm => pm.person))
              memberCache.put(listMembers.projectId.toString, persons)
              sender() ! persons
            case e: JsError =>
              log.error(s"Unable to parse response from Pivotal: ${JsError.toJson(e)}")
              sender() ! e
          }
        }
      }

    case c: CreateChore =>
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
        ownerIds = owners,
        currentState = "started" //We want quick-chores to be started by default
      )

      //Post that payload to pivotal
      log.debug(s"Trying to post to $baseUrl/projects/${c.projectId}/stories")
      val request = new HttpPost(s"$baseUrl/projects/${c.projectId}/stories")
      val payloadString = Json.toJson(payload) toString()
      request.setEntity(EntityBuilder.create()
        .setText(payloadString)
        .setContentType(ContentType.APPLICATION_JSON)
        .build())
      request.addHeader("X-TrackerToken", trackerToken)

      handleResponse(httpClient.execute(request, null).get()) { response =>
        //sent!
        Json.parse(response.getEntity.getContent).validate[PivotalStory] match {
          case s: JsSuccess[PivotalStory] =>
            //Worked! got details
            sender() ! s.get
          case e: JsError =>
            log.error(s"Wasn't able to successfully create story, or I couldn't read their JSON: ${JsError.toJson(e)}")
            sender() ! e
        }
      }
  }

  /**
    * Handle the 401 403, and possibly 500 error types as well as a fallback to any other type
    * TODO: 502, 504 could come from the proxy I think?
    *
    * @return
    */
  def handleResponse(response: HttpResponse)(success: (HttpResponse) => Unit): Unit = {
    response.getStatusLine.getStatusCode match {
      case 200 | 201 | 202 =>
        log.debug("Successful request!")
        success(response) //Call the success part of the function
      case 502 | 504 =>
        //Perhaps the proxy prevented a request from going through?
        log.warning("Received 502 or 504, perhaps the proxy rejected our request for some reason?")
        sender() ! PivotalError(kind = "proxy",
          code = response.getStatusLine.getStatusCode.toString,
          error = response.getStatusLine.getReasonPhrase,
          generalProblem = Some("received a bad gateway type response from the proxy, hopefully this is a transient error"))
      case 404 =>
        log.info("Couldn't find the requested item, probably okay")
      case _ => //Anything else is a legit pivotal error
        Json.parse(response.getEntity.getContent).validate[PivotalError] match {
          case s: JsSuccess[PivotalError] =>
            sender() ! s.get
          case e: JsError =>
            log.error(s"I couldn't parse the error: ${JsError.toJson(e)}")
            sender() ! PivotalError(
              kind = "unknown",
              code = response.getStatusLine.getStatusCode.toString,
              error = response.getStatusLine.getReasonPhrase,
              generalProblem = Some("I couldn't even parse the error for this, something failed hard, check the logs!")
            )
        }
    }
  }
}
