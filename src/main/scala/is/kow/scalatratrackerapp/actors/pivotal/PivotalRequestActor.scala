package is.kow.scalatratrackerapp.actors.pivotal

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import com.google.common.cache.{Cache, CacheBuilder, CacheStats}
import com.mashape.unirest.http.{HttpResponse => UnirestHttpResponse}
import is.kow.scalatratrackerapp.AppConfig
import is.kow.scalatratrackerapp.actors.HttpRequestActor.{GetRequest, PostRequest, RequestFailed, Response}
import nl.grons.metrics.scala.DefaultInstrumented
import play.api.libs.json.{JsError, JsSuccess, Json}

import scala.util.{Failure, Success}

object PivotalRequestActor {

  def props(httpActor: ActorRef) = Props(new PivotalRequestActor(httpActor))

  case class GetStoryDetails(projectId: Long, storyId: Long)

  case class GetLabels(projectId: Long)

  case class LabelsList(labels: List[PivotalLabel])

  //TODO: implement a chore creation that puts it in the current iteration too
  case class CreateChore(projectId: Long, name: String, assignToId: Option[Long], requesterId: Long, description: Option[String], started: Boolean)

  case class ItemCreated(projectId: Long, itemId: Long)

  //https://www.pivotaltracker.com/help/api/rest/v5#projects_project_id_memberships_get
  case class ListMembers(projectId: Long)

  case class Members(members: List[PivotalPerson])

  //This is gonna be common
  case object StoryNotFound

  case class PivotalRequestFailure(message: String)

}

class PivotalRequestActor(httpActor: ActorRef) extends Actor with ActorLogging with DefaultInstrumented {

  import PivotalRequestActor._

  //Needed for the asks
  private implicit val executionContext = context.dispatcher

  private val config = AppConfig.config

  private val labelCache: Cache[String, LabelsList] = CacheBuilder.
    newBuilder().
    expireAfterWrite(1, TimeUnit.MINUTES).
    build[String, LabelsList]()

  //Had to use strings, because of the java generics things
  private val memberCache: Cache[String, Members] = CacheBuilder.
    newBuilder().
    expireAfterWrite(30, TimeUnit.MINUTES). //TODO: this could probably be a whole lot longer
    build[String, Members]()

  private val baseUrl = config.getString("tracker.base")

  private val trackerToken = config.getString("tracker.token")

  //the whole purpose of this class is to marshall json!
  import PivotalResponseJsonImplicits._

  //Metrics
  private val storyDetailsRequests = metrics.timer("pivotal.story_details")
  private val labelsRequests = metrics.timer("pivotal.labels")
  private val membersRequests = metrics.timer("pivotal.members")
  private val choreCreationRequests = metrics.timer("pivotal.chore_creation")

  //plop some gauges about our caches!
  def cacheMetrics(stats: CacheStats, name: String): Unit = {
    //Send the metrics to a metrics actor?
    //TODO: this creates the metrics every time, I need to get it or create it if it's not there
    //TODO: how do I get a metric to use again over and over?
    //TODO: Some way of creating updating these metrics easily
    //metrics.registry.gauge(s"pivotal.cache.${name}.evictionCount")
    //metrics.gauge[Long](s"pivotal.cache.${name}.evictionCount")(stats.evictionCount())
    //metrics.gauge[Long](s"pivotal.cache.${name}.hitCount")(stats.hitCount())
    //metrics.gauge[Double](s"pivotal.cache.${name}.hitRate")(stats.hitRate())
  }

  val pivotalHeaders:Map[String, String] = Map("X-TrackerToken" -> trackerToken)

  import akka.pattern.ask

  import scala.concurrent.duration._

  def receive = {
    //Read-only task
    case storyDetails: GetStoryDetails =>
      val theSender = sender()
      log.debug("Got a request for story details!")
      val storyUrl = baseUrl + s"/projects/${storyDetails.projectId}/stories/${storyDetails.storyId}"

      //Get a future back, and explicitly set a 15 second timeout
      //Wrap that future in a timer, so I know how long it takes for that future to complete
      val responseFuture = storyDetailsRequests.timeFuture(httpActor.ask(GetRequest(storyUrl, pivotalHeaders))(15 seconds))

      responseFuture.onSuccess {
        case Response(response) =>
          //TODO: this JSON parsing stuff kinda sucks.
          response.getStatus match {
            case 200 =>
              Json.parse(response.getBody).validate[PivotalStory] match {
                case s: JsSuccess[PivotalStory] =>
                  //Give the sender back the Pivotal Story
                  theSender ! s.get
                case e: JsError =>
                  //TODO: this should send back some other kind of error
                  log.error(s"Unable to parse response from Pivotal: ${Json.prettyPrint(JsError.toJson(e))}")
                  theSender ! PivotalRequestFailure(
                    s"""
                       |Could not parse JSON from pivotal tracker!
                       |```
                       |${Json.prettyPrint(JsError.toJson(e))}
                       |```
                 """.stripMargin)
              }
            case 404 =>
              //No story found, and that's okay
              theSender ! StoryNotFound
            case _ =>
              //Something else happened!
              log.error(s"Unexpected response from pivotal: ${response.getStatus}")
              theSender ! PivotalRequestFailure(
                s"""
                   |Unexpected response from Pivotal Tracker!
                   |```
                   |${response.getBody}
                   |```
                 """.stripMargin
              )
          }

        case RequestFailed(request, Some(exception)) =>
          log.error(s"Unable to complete story details request ${request}. Exception: ${exception.getMessage}")
          theSender ! PivotalRequestFailure(s"Unable to complete story details request: `${exception.getMessage}")
        case RequestFailed(request, None) =>
          log.error(s"Unable to complete story details request ${request}. No exception given.")
          theSender ! PivotalRequestFailure("Unable to complete story details request. No exception given :(")
      }
      responseFuture.onFailure {
        case e: Exception =>
          log.error(s"ERROR waiting on Story Details Response: ${e.getMessage}")
          //TODO: any exception is fine, need to relay back that it failed
          //Could be a timeout exception or something else
          theSender ! PivotalRequestFailure(s"Failed to complete Story Details Request: `${e.getMessage}")
      }

    //Read-only operation
    case labels: GetLabels => {
      val theSender = sender()
      cacheMetrics(labelCache.stats(), "labels")
      Option(labelCache.getIfPresent(labels.projectId.toString)).map { labelList =>
        log.debug(s"My actor ref to reply is: ${sender().toString}")
        sender() ! labelList //need to encapsulate it because erasure
      } getOrElse {
        val labelUrl = baseUrl + s"/projects/${labels.projectId}/labels"
        val responseFuture = labelsRequests.timeFuture(httpActor.ask(GetRequest(labelUrl, pivotalHeaders))(15 seconds))
        responseFuture onComplete {
          case Success(Response(response)) =>
            //Parse the json
            if (response.getStatus == 200) {
              Json.parse(response.getBody).validate[List[PivotalLabel]] match {
                case s: JsSuccess[List[PivotalLabel]] =>
                  labelCache.put(labels.projectId.toString, LabelsList(s.get))
                  theSender ! LabelsList(s.get)
                case e: JsError =>
                  log.error(s"Unable to parse labels response from pivotal")
                  theSender ! PivotalRequestFailure(
                    s"""
                       |Could not parse JSON from pivotal tracker!
                       |```
                       |${Json.prettyPrint(JsError.toJson(e))}
                       |```
                 """.stripMargin)
              }
            } else {
              log.error("Didn't receive a successful response to a labels request")
              theSender ! PivotalRequestFailure(s"Error from pivotal: ${response.getStatus}")
            }
          case Success(RequestFailed(request, Some(exception))) =>
            log.error(s"Unable to complete labels request ${request}. Exception: ${exception.getMessage}")
            theSender ! PivotalRequestFailure(s"Unable to complete labels request: `${exception.getMessage}")
          case Success(RequestFailed(request, None)) =>
            log.error(s"Unable to complete labels request ${request}. No exception given.")
            theSender ! PivotalRequestFailure("Unable to complete labels request. No exception given :(")
          case Failure(exception) =>
            //Create a generic failure message for the future
            log.error(s"ERROR waiting on Labels Response: ${exception.getMessage}")
            //Could be a timeout exception or something else
            theSender ! PivotalRequestFailure(s"Failed to complete Labels Request: `${exception.getMessage}")
        }
      }
    }

    case listMembers: ListMembers =>
      log.debug("Asking for members!")
      val theSender = sender()
      cacheMetrics(memberCache.stats(), "members")
      Option(memberCache.getIfPresent(listMembers.projectId)).map { membersList =>
        theSender ! membersList
      } getOrElse {
        //TODO: why do I sometimes get a list, but not always?
        val membersUrl = s"$baseUrl/projects/${listMembers.projectId}/memberships"
        val responseFuture = labelsRequests.timeFuture(httpActor.ask(GetRequest(membersUrl, pivotalHeaders))(15 seconds))
        responseFuture onComplete {
          case Success(Response(response)) =>
            //Parse the json
            if (response.getStatus == 200) {
              Json.parse(response.getBody).validate[List[PivotalMember]] match {
                case s: JsSuccess[List[PivotalMember]] =>
                  val persons = Members(s.get.map(pm => pm.person))
                  memberCache.put(listMembers.projectId.toString, persons)
                  theSender ! persons
                case e: JsError =>
                  log.error(s"Unable to parse members response from pivotal")
                  theSender ! PivotalRequestFailure(
                    s"""
                       |Could not parse JSON from pivotal tracker!
                       |```
                       |${Json.prettyPrint(JsError.toJson(e))}
                       |```
                       |Payload:
                       |```
                       |${response.getBody}
                       |```
                 """.stripMargin)
              }
            } else {
              log.error("Didn't receive a successful response to a members request")
              theSender ! PivotalRequestFailure(s"Error from pivotal: ${response.getStatus}")
            }
          case Success(RequestFailed(request, Some(exception))) =>
            log.error(s"Unable to complete members request ${request}. Exception: ${exception.getMessage}")
            theSender ! PivotalRequestFailure(s"Unable to complete members request: `${exception.getMessage}")
          case Success(RequestFailed(request, None)) =>
            log.error(s"Unable to complete members request ${request}. No exception given.")
            theSender ! PivotalRequestFailure("Unable to complete members request. No exception given :(")
          case Failure(exception) =>
            //Create a generic failure message for the future
            log.error(s"ERROR waiting on members Response: ${exception.getMessage}")
            //Could be a timeout exception or something else
            theSender ! PivotalRequestFailure(s"Failed to complete members Request: `${exception.getMessage}")
        }
      }

    case c: CreateChore =>
      log.debug("Creating chore!")
      val owners = c.assignToId.map { id =>
        List(id)
      } getOrElse {
        List.empty[Long]
      }

      //TODO: convert this to the spray json, instead of play-json, to remove that dependency
      import PivotalRequestJsonImplicits._
      val payload = PivotalStoryCreation(
        projectId = c.projectId,
        name = c.name,
        storyType = "chore",
        description = c.description,
        ownerIds = owners,
        requestedById = Some(c.requesterId)
      )
      log.debug(s"PAYLOAD: ${payload}")
      log.debug(s"JSON Payload:\n${
        Json.toJson(payload).toString()
      }")
      val theSender = sender()

      val storyUrl = s"$baseUrl/projects/${c.projectId}/stories"
      val postHeaders = pivotalHeaders + ("content-type" -> "application/json")
      val responseFuture = choreCreationRequests
        .timeFuture(httpActor.ask(PostRequest(storyUrl, postHeaders, Json.toJson(payload).toString()))(15 seconds))
      responseFuture onComplete {
        case Success(Response(response)) =>
          //Parse the json
          if (response.getStatus == 200) {
            Json.parse(response.getBody).validate[PivotalStory] match {
              case s: JsSuccess[PivotalStory] =>
                //Worked, just send back the Story
                theSender ! s.get
              case e: JsError =>
                log.error(s"Unable to parse Story response from creating chore pivotal")
                theSender ! PivotalRequestFailure(
                  s"""
                     |Could not parse JSON from pivotal tracker!
                     |```
                     |${Json.prettyPrint(JsError.toJson(e))}
                     |```
                     |Payload:
                     |```
                     |${response.getBody}
                     |```
                 """.stripMargin)
            }
          } else {
            log.error("Didn't receive a successful response to creating a chore")
            theSender ! PivotalRequestFailure(s"Error from pivotal: ${response.getStatus}\n```${response.getBody}```")
          }
        case Success(RequestFailed(request, Some(exception))) =>
          log.error(s"Unable to complete members request ${request}. Exception: ${exception.getMessage}")
          theSender ! PivotalRequestFailure(s"Unable to complete chore creation request: `${exception.getMessage}")
        case Success(RequestFailed(request, None)) =>
          log.error(s"Unable to complete members request ${request}. No exception given.")
          theSender ! PivotalRequestFailure("Unable to complete chore creation request. No exception given :(")
        case Failure(exception) =>
          //Create a generic failure message for the future
          log.error(s"ERROR waiting on Chore Creation Response: ${exception.getMessage}")
          //Could be a timeout exception or something else
          theSender ! PivotalRequestFailure(s"Failed to complete Chore Creation Request: `${exception.getMessage}")
      }
  }

//  def handleResponse[T](activityName: String)(successfulParse: T => Unit) = {
//    case Success(Response(response)) =>
//      if(response.getStatus == 200) {
//        Json.parse(response.getBody  ).validate[T] match {
//          case s: JsSuccess[T] =>
//            successfulParse(s.get)
//          case e: JsError =>
//            log.error(s"Unable to parse Story response from creating chore pivotal")
//            theSender ! PivotalRequestFailure(
//              s"""
//                 |Could not parse JSON from pivotal tracker!
//                 |```
//                 |${Json.prettyPrint(JsError.toJson(e))}
//                 |```
//                 |Payload:
//                 |```
//                 |${response.getBody}
//                 |```
//                 """.stripMargin)
//
//        }
//      }
  //  }
}
