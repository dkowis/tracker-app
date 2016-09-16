package is.kow.scalatratrackerapp.actors.pivotal

import com.github.tototoshi.play.json.JsonNaming

//Valid story type values: feature, bug, chore, release
//Valid current_state values: accepted, delivered, finished, started, rejected, planned, unstarted, unscheduled

case class PivotalStoryCreation(
                                 projectId: Long,
                                 name: String,
                                 description: Option[String] = None,
                                 storyType: String,
                                 requestedById: Option[Long] = None,
                                 ownerIds: List[Long] = List.empty[Long],
                                 currentState: String = "unstarted"
                               )


object PivotalRequestJsonImplicits {

  import play.api.libs.json._

  //Oh god yes: https://www.playframework.com/documentation/2.5.x/ScalaJsonAutomated
  implicit val pivotalStoryCreationWriter = JsonNaming.snakecase(Json.writes[PivotalStoryCreation])
}