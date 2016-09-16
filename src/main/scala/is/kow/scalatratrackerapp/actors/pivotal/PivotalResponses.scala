package is.kow.scalatratrackerapp.actors.pivotal

import com.github.tototoshi.play.json.JsonNaming
import org.joda.time.DateTime


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

/*
{
   "created_at": "2016-09-13T12:00:00Z",
   "current_state": "unscheduled",
   "id": 2300,
   "kind": "story",
   "labels":
   [
   ],
   "name": "Exhaust ports are ray shielded",
   "owner_ids":
   [
   ],
   "project_id": 99,
   "requested_by_id": 101,
   "story_type": "feature",
   "updated_at": "2016-09-13T12:00:00Z",
   "url": "http://localhost/story/show/2300"
}

 */
case class PivotalItemCreated(
                             createdAt: DateTime,
                             currentState: String,
                             id: Long,
                             kind: String,
                             labels: List[PivotalLabel],
                             name: String,
                             ownerIds: List[Long],
                             projectId: String,
                             requestedById: Long,
                             storyType: String,
                             updatedAt: DateTime,
                             url: String
                           )

object PivotalResponseJsonImplicits {

  import play.api.libs.json._

  //Oh god yes: https://www.playframework.com/documentation/2.5.x/ScalaJsonAutomated
  implicit val pivotalLabelReader = JsonNaming.snakecase(Json.reads[PivotalLabel])
  implicit val pivotalStoryReader = JsonNaming.snakecase(Json.reads[PivotalStory])
  implicit val pivotalPersonReader = JsonNaming.snakecase(Json.reads[PivotalPerson])
  implicit val pivotalMemberReader = JsonNaming.snakecase(Json.reads[PivotalMember])
  implicit val pivotalItemCreatedReader = JsonNaming.snakecase(Json.reads[PivotalItemCreated])
}