package is.kow.scalatratrackerapp.actors.pivotal

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, Props}
import com.google.common.cache.{Cache, CacheBuilder, CacheStats}
import is.kow.scalatratrackerapp.AppConfig
import nl.grons.metrics.scala.DefaultInstrumented
import org.apache.http.client.entity.EntityBuilder
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.entity.ContentType
import org.apache.http.impl.nio.client.HttpAsyncClients
import org.apache.http.message.BasicHeader
import org.apache.http.{HttpHost, HttpResponse}
import play.api.libs.json.{JsError, JsSuccess, Json}

object PivotalRequestActor {

  def props = Props[PivotalRequestActor]

  case class StoryDetails(projectId: Long, storyId: Long)

  case class Labels(projectId: Long)

  case class LabelsList(labels: List[PivotalLabel])

  //TODO: implement a chore creation that puts it in the current iteration too
  case class CreateChore(projectId: Long, name: String, assignToId: Option[Long], requesterId: Long, description: Option[String], started:Boolean)

  case class ItemCreated(projectId: Long, itemId: Long)

  //https://www.pivotaltracker.com/help/api/rest/v5#projects_project_id_memberships_get
  case class ListMembers(projectId: Long)

  case class Members(members: List[PivotalPerson])

  //This is gonna be common
  case object StoryNotFound

}

class PivotalRequestActor extends Actor with ActorLogging with DefaultInstrumented {

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
      builder.setProxy(proxy)
    }

    //Always put the tracker token in my headers
    import scala.collection.JavaConverters._
    builder.setDefaultHeaders(List(new BasicHeader("X-TrackerToken", trackerToken)).asJava)
    builder.build()
  }

  //Don't forget to start the httpclient
  httpClient.start()

  //TODO: at some point shut it off

  //Metrics
  private val storyDetailsRequests = metrics.timer("pivotal.story_details")
  private val labelsRequests = metrics.timer("pivotal.labels")
  private val membersRequests = metrics.timer("pivotal.members")
  private val choreCreationRequests = metrics.timer("pivotal.chore_creation")

  //plop some gauges about our caches!
  def cacheMetrics(stats: CacheStats, name: String):Unit  = {
    //Send the metrics to a metrics actor?
    //TODO: this creates the metrics every time, I need to get it or create it if it's not there
    //TODO: how do I get a metric to use again over and over?
    //TODO: Some way of creating updating these metrics easily
    //metrics.registry.gauge(s"pivotal.cache.${name}.evictionCount")
    //metrics.gauge[Long](s"pivotal.cache.${name}.evictionCount")(stats.evictionCount())
    //metrics.gauge[Long](s"pivotal.cache.${name}.hitCount")(stats.hitCount())
    //metrics.gauge[Double](s"pivotal.cache.${name}.hitRate")(stats.hitRate())
  }


  def receive = {
    //Read-only task
    case storyDetails: StoryDetails => {
      log.debug("Got a request for story details!")
      val storyUrl = baseUrl + s"/projects/${storyDetails.projectId}/stories/${storyDetails.storyId}"

      storyDetailsRequests.time {
        val request = new HttpGet(storyUrl)
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
    }

    //Read-only operation
    case labels: Labels => {
      cacheMetrics(labelCache.stats(), "labels")
      Option(labelCache.getIfPresent(labels.projectId.toString)).map { labelList =>
        log.debug(s"My actor ref to reply is: ${sender().toString}")
        sender() ! labelList //need to encapsulate it because erasure
      } getOrElse {
        val labelUrl = baseUrl + s"/projects/${labels.projectId}/labels"
        labelsRequests.time {
          val request = new HttpGet(labelUrl)
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
    }

    case listMembers: ListMembers =>
      log.debug("Asking for members!")
      cacheMetrics(memberCache.stats(), "members")
      Option(memberCache.getIfPresent(listMembers.projectId)).map { membersList =>
        sender() ! membersList
      } getOrElse {
        val membersUrl = s"$baseUrl/projects/${listMembers.projectId}/memberships"
        membersRequests.time {
          val request = new HttpGet(membersUrl)
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
      }

    case c: CreateChore =>
      log.debug("Creating chore!")
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
        requestedById = Some(c.requesterId),
        currentState = {
          if(c.started) {
            "started"
          } else {
            "unstarted"
          }
        }
      )

      //Post that payload to pivotal
      log.debug(s"Trying to post to $baseUrl/projects/${c.projectId}/stories")
      choreCreationRequests.time {
        val request = new HttpPost(s"$baseUrl/projects/${c.projectId}/stories")
        val payloadString = Json.toJson(payload) toString()
        request.setEntity(EntityBuilder.create()
          .setText(payloadString)
          .setContentType(ContentType.APPLICATION_JSON)
          .build())

        handleResponse(httpClient.execute(request, null).get()) { response =>
          //sent!
          Json.parse(response.getEntity.getContent).validate[PivotalStory] match {
            case s: JsSuccess[PivotalStory] =>
              //Worked! got details
              sender() ! s.get
            case e: JsError =>
              log
                .error(s"Wasn't able to successfully create story, or I couldn't read their JSON: ${JsError.toJson(e)}")
              sender() ! e
          }
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
        sender ! StoryNotFound
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
