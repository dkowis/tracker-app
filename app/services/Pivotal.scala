package services

import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}

import com.google.common.cache.{Cache, CacheBuilder, LoadingCache}
import org.joda.time.DateTime
import play.api.{Configuration, Logger}
import play.api.libs.json.{JsError, JsSuccess, JsValue}
import play.api.libs.ws.WSClient

import scala.concurrent.{Await, ExecutionContext, Future}

/**
  * Interface to pivotal tracker!
  */
@Singleton
class Pivotal @Inject()(configuration: Configuration, ws: WSClient)(implicit context: ExecutionContext) {

  val labelCache: Cache[String, PivotalLabel] = CacheBuilder.
    newBuilder().
    expireAfterWrite(30, TimeUnit.SECONDS).
    build[String, PivotalLabel]()

  /**
    * Gets the story details, and returns the Case classes, basically the attachment
    *
    * @param projectId
    * @param storyId
    * @return
    */
  def storyDetails(projectId: Long, storyId: Long): Option[Future[SlackAttachment]] = {
    val baseUrlOption = configuration.getString("tracker.base")
    val trackerTokenOption = configuration.getString("tracker.token")
    val projectIdOption = configuration.getLong("project_id")

    val response: Option[Future[SlackAttachment]] = for {
      baseUrl <- baseUrlOption;
      trackerToken <- trackerTokenOption
      projectId <- projectIdOption
    } yield {
      val storyUrl = baseUrl + s"/projects/$projectId/stories/$storyId"

      val slackResponse: Future[SlackAttachment] = ws.url(storyUrl)
        .withHeaders("X-TrackerToken" -> trackerToken).get().map { response =>
        //got a response now, so lets build the response we're going to return to

        //Gonna have to resolve some of the things in those responses, probably use guava cache to make it happen
        import PivotalJsonImplicits._
        val pivotalStory = response.json.validate[PivotalStory]

        pivotalStory match {
          case s: JsSuccess[PivotalStory] => {
            //Parse the junk into a SlackAttachment
            val pivotalStory = s.get

            //Need to resolve all the labels
            val labels: String = pivotalStory.labels.flatMap { label =>
              labelName(projectId, label.id)
            }.mkString(", ")

            SlackAttachment(
              title = pivotalStory.name,
              fallback = pivotalStory.name,
              title_link = Some(pivotalStory.url),
              text = pivotalStory.description, //TODO: maybe render this via markdown into HTMLs?
              fields = Some(List(
                SlackField(title = "State", value = pivotalStory.currentState, short = true),
                SlackField(title = "Type", value = pivotalStory.storyType, short = true),
                SlackField(title = "Labels", value = labels, short = false)
              )),
              footer = Some("TrackerApp - updated at"),
              ts = Some(DateTime.parse(pivotalStory.updatedAt).getMillis),
              footerIcon = Some("/assets/images/Tracker_Icon.svg") //TODO: figure out how to get the proper hostname
            )
          }
          case e: JsError => {
            //epic fail, send back a different slack message, and log errors
            Logger.error(s"Unable to parse json! $e")
            SlackAttachment(
              title = "Something went wrong parsing the json from Pivotal!",
              fallback = "Something went wrong",
              text = e.toString
            )
          }
        }
      }

      slackResponse
    }

    response
  }

  /**
    * TODO this isn't asynchronous, gonna block on it, until I can figure out how to do things the right way
    *
    * @param labelId
    * @return
    */
  def labelName(projectId: Long, labelId: Long): Option[String] = {
    //Get all the labels, stick them in a guava cache for 30 seconds or so
    val baseUrlOption = configuration.getString("tracker.base")
    val trackerTokenOption = configuration.getString("tracker.token")

    val labelKey = s"${projectId}_${labelId}"

    val labelOption = Option(labelCache.getIfPresent(labelKey))

    if (labelOption.isEmpty) {
      //Make the http request to get all the labels, and try the cache again
      for {
        baseUrl <- baseUrlOption
        trackerToken <- trackerTokenOption
      } yield {
        //TODO: turning async code into sync code because I've forgotten how to do ihis again
        import scala.concurrent.duration._
        val response = Await.result(ws.url(s"$baseUrl/projects/$projectId/labels")
          .withHeaders("X-TrackerToken" -> trackerToken).get(), 30.seconds)

        import PivotalJsonImplicits._
        response.json.validate[List[PivotalLabel]] match {
          case s: JsSuccess[List[PivotalLabel]] => {
            s.get.foreach { label =>
              labelCache.put(s"${projectId}_${label.id}", label)
            }
          }
          case e: JsError => {
            Logger.error("Unable to acquire label list from project")
            "CouldNotResolveLabel" //TODO: this is gross
          }
        }

        //TODO: this could still throw a null pointer exception! Shame on me!
        labelCache.getIfPresent(labelKey).name
      }
    } else {
      labelOption.map(pl => pl.name)
    }
  }
}

