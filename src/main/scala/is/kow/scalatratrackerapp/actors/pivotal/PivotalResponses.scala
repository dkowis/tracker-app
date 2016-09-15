package is.kow.scalatratrackerapp.actors.pivotal

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
                         description: Option[String],
                         currentState: String,
                         requestedById: Long,
                         url: String,
                         projectId: Long,
                         ownerIds: List[Long],
                         labels: List[PivotalLabel],
                         ownedById: Option[Long]
                       )


case class PivotalPerson(
                          id: Long,
                          name: String,
                          email: String,
                          initials: String,
                          username: String
                        )

case class PivotalMember(
                          id: Long,
                          person: PivotalPerson,
                          role: String
                        )

object PivotalJsonImplicits {
  import play.api.libs.json._

  //Oh god yes: https://www.playframework.com/documentation/2.5.x/ScalaJsonAutomated
  implicit val pivotalLabelReader = JsonNaming.snakecase(Json.reads[PivotalLabel])
  implicit val pivotalStoryReader = JsonNaming.snakecase(Json.reads[PivotalStory])
  implicit val pivotalPersonReader = JsonNaming.snakecase(Json.reads[PivotalPerson])
  implicit val pivotalMemberReader = JsonNaming.snakecase(Json.reads[PivotalMember])
}