package services

import com.github.tototoshi.play.json.JsonNaming


case class PivotalLabel(
                         id: Long,
                         projectId: Long,
                         kind: String,
                         name: String,
                         createdAt: String,
                         updatedAt: String
                       )

case class PivotalStory(
                         kind: String,
                         id: Long,
                         createdAt: String,
                         updatedAt: String,
                         storyType: String,
                         name: String,
                         description: String,
                         currentState: String,
                         requestedById: Long,
                         url: String,
                         projectId: Long,
                         ownerIds: List[Long],
                         labels: List[PivotalLabel],
                         ownedById: Long
                       )

object PivotalJsonImplicits {
  import play.api.libs.json._

  //Oh god yes: https://www.playframework.com/documentation/2.5.x/ScalaJsonAutomated
  implicit val pivotalLabelReader = JsonNaming.snakecase(Json.reads[PivotalLabel])
  implicit val pivotalStoryReader = JsonNaming.snakecase(Json.reads[PivotalStory])
}