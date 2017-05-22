package is.kow.scalatratrackerapp.actors.pivotal

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import com.google.common.cache.{Cache, CacheBuilder, CacheStats}
import is.kow.scalatratrackerapp.AppConfig
import is.kow.scalatratrackerapp.actors.HttpRequestActor.{GetRequest, PostRequest, RequestFailed, Response}
import is.kow.scalatratrackerapp.actors.pivotal.PivotalRequestActor.IterationType.IterationType
import nl.grons.metrics.scala.{DefaultInstrumented, Timer}
import spray.json.JsonParser

import scala.util.{Failure, Success, Try}

object PivotalRequestActor {

  import PivotalResponses._

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

  //An iteration type to determine which one we want
  object IterationType extends Enumeration {
    type IterationType = Value
    val Current, Previous, Backlog = Value
  }

  case class GetIteration(projectId: Long, scope: IterationType = IterationType.Current) //Default to the current iteration

  case class PivotalRequestFailure(message: String)

}

class PivotalRequestActor(httpActor: ActorRef) extends Actor with ActorLogging with DefaultInstrumented {

  import PivotalResponses._
  import PivotalRequestActor._

  //Needed for the asks
  private implicit val executionContext = context.dispatcher

  private val config = AppConfig.config

  //I'm not even sure I ned a label cache.... labels come back with the story
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

  //Importing the spray-json formats
  import PivotalJsonProtocol._

  //Metrics
  private val storyDetailsRequests = metrics.timer("pivotal.story_details")
  private val iterationRequests = metrics.timer("pivotal.iteration")
  private val labelsRequests = metrics.timer("pivotal.labels")
  private val membersRequests = metrics.timer("pivotal.members")
  private val choreCreationRequests = metrics.timer("pivotal.chore_creation")

  //plop some gauges about our caches!
  //TODO: need to create the gauges on actor startup, if they don't exist
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

  val pivotalHeaders: Map[String, String] = Map("X-TrackerToken" -> trackerToken)

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
      val responseFuture = storyDetailsRequests.timeFuture(httpActor.ask(GetRequest(storyUrl, pivotalHeaders))(15.seconds))

      responseFuture.onComplete {
        handleFailures("Story Details", theSender) orElse {
          case Success(Response(response)) =>
            //TODO: this JSON parsing stuff kinda sucks.
            response.getStatus match {
              case 200 =>
                //TODO: spray.json.DeserializationException is thrown if it doesn't properly de-serialize...
                val pivotalStory = JsonParser(response.getBody).convertTo[PivotalStory]
                theSender ! pivotalStory
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
        }
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
        val responseFuture = labelsRequests.timeFuture(httpActor.ask(GetRequest(labelUrl, pivotalHeaders))(15.seconds))
        responseFuture onComplete {
          handleFailures("Getting Labels", theSender) orElse {
            case Success(Response(response)) =>
              //Parse the json
              if (response.getStatus == 200) {
                val labelList = LabelsList(JsonParser(response.getBody).convertTo[List[PivotalLabel]])
                labelCache.put(labels.projectId.toString, labelList)
                theSender ! labelList
              } else {
                log.error("Didn't receive a successful response to a labels request")
                theSender ! PivotalRequestFailure(s"Error from pivotal: ${response.getStatus}")
              }
          }
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
        val responseFuture = membersRequests.timeFuture(httpActor.ask(GetRequest(membersUrl, pivotalHeaders))(15.seconds))
        responseFuture onComplete {
          handleFailures("listing members", theSender) orElse {
            case Success(Response(response)) =>
              //Parse the json
              if (response.getStatus == 200) {
                val membersList = Members(JsonParser(response.getBody).convertTo[List[PivotalMember]].map(pm => pm.person))
                memberCache.put(listMembers.projectId.toString, membersList)
                theSender ! membersList
              } else {
                log.error("Didn't receive a successful response to a members request")
                theSender ! PivotalRequestFailure(s"Error from pivotal: ${response.getStatus}")
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

      //TODO: convert this to the spray json, instead of play-json, to remove that dependency
      val payload = PivotalStoryCreation(
        projectId = c.projectId,
        name = c.name,
        storyType = "chore",
        description = c.description,
        ownerIds = owners,
        requestedById = Some(c.requesterId)
      )
      import spray.json._
      import PivotalRequestFormat._
      val jsonPayload = payload.toJson
      log.debug(s"PAYLOAD: ${payload}")
      log.debug(s"JSON Payload:\n${jsonPayload.prettyPrint}")
      val theSender = sender()

      val storyUrl = s"$baseUrl/projects/${c.projectId}/stories"
      val postHeaders = pivotalHeaders + ("content-type" -> "application/json")
      val responseFuture = choreCreationRequests
        .timeFuture(httpActor.ask(PostRequest(storyUrl, postHeaders, jsonPayload.compactPrint))(15.seconds))
      responseFuture onComplete {
        handleFailures("creating chore", theSender) orElse {
          case Success(Response(response)) =>
            //Parse the json
            if (response.getStatus == 200) {
              val pivotalStory = JsonParser(response.getBody).convertTo[PivotalStory]
              theSender ! pivotalStory
            } else {
              log.error("Didn't receive a successful response to creating a chore")
              theSender ! PivotalRequestFailure(s"Error from pivotal: ${response.getStatus}\n```${response.getBody}```")
            }
        }
      }
    case getIteration: GetIteration =>
      val theSender = sender()
      log.debug(s"Got a request for the ${getIteration.scope} iteration!")
      //https://www.pivotaltracker.com/services/v5/projects/<projectId>/iterations?scope=current
      val iterationUrl = baseUrl + s"/projects/${getIteration.projectId}/iteration" +
        (getIteration.scope match {
          case IterationType.Backlog => "?scope=backlog&offset=0"
          case IterationType.Current => "?scope=current&offset=0"
          case IterationType.Previous => "?scope=done&offset=-1"
        })

      //Get a future back, and explicitly set a 15 second timeout
      //Wrap that future in a timer, so I know how long it takes for that future to complete
      val responseFuture = iterationRequests.timeFuture(httpActor.ask(GetRequest(iterationUrl, pivotalHeaders))(15.seconds))

      responseFuture.onComplete {
        handleFailures("Story Details", theSender) orElse {
          case Success(Response(response)) =>
            response.getStatus match {
              case 200 =>
                //TODO: spray.json.DeserializationException is thrown if it doesn't properly de-serialize...
                val pivotalIteration = JsonParser(response.getBody).convertTo[Iteration]
                theSender ! pivotalIteration
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
        }
      }

  }

  /**
    * Wrap up all the failures so I don't have to repeat myself every time
    *
    * @param activityName
    * @param theSender
    * @return
    */
  def handleFailures(activityName: String, theSender: ActorRef): PartialFunction[Try[Any], Unit] = {
    case Success(RequestFailed(request, Some(exception))) =>
      log.error(s"Unable to complete members request $request. Exception: ${exception.getMessage}")
      theSender ! PivotalRequestFailure(s"Unable to complete $activityName: `${exception.getMessage}")
    case Success(RequestFailed(request, None)) =>
      log.error(s"Unable to complete members request $request. No exception given.")
      theSender ! PivotalRequestFailure(s"Unable to complete $activityName. No exception given :(")
    case Failure(exception) =>
      //Create a generic failure message for the future
      log.error(s"ERROR waiting on $activityName Response: ${exception.getMessage}")
      //Could be a timeout exception or something else
      theSender ! PivotalRequestFailure(s"Failed to complete $activityName: `${exception.getMessage}")
  }
}
