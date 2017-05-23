package is.kow.scalatratrackerapp.actors.pivotal

import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import spray.json.{JsString, JsValue, RootJsonFormat}

object PivotalResponses {

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
                           deadline: Option[DateTime],
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

  case class Iteration(
                        number: Int,
                        projectId: Int,
                        length: Int,
                        teamStrength: Int,
                        stories: List[PivotalStory],
                        start: DateTime,
                        finish: DateTime,
                        kind: String
                      )

  case class PivotalValidationError(
                                     field: String,
                                     problem: String
                                   )

  case class PivotalError(
                           kind: String,
                           code: String,
                           error: String,
                           requirement: Option[String] = None,
                           generalProblem: Option[String] = None,
                           possibleFix: Option[String] = None,
                           validationErrors: Option[List[PivotalValidationError]] = None
                         )

}

//Spray JSON format for reading objects
object PivotalJsonProtocol extends SnakifiedJsonSupport {

  import PivotalResponses._

  implicit object DateTimeFormat extends RootJsonFormat[DateTime] {

    val formatter = org.joda.time.format.DateTimeFormat.forPattern("YYYY-MM-dd'T'HH:mm:ss'Z")

    def write(obj: DateTime): JsValue = {
      JsString(formatter.print(obj))
    }

    def read(json: JsValue): DateTime = json match {
      case JsString(s) => try {
        formatter.parseDateTime(s)
      }
      catch {
        case t: Throwable => error(s)
      }
      case _ =>
        error(json.toString())
    }

    def error(v: Any): DateTime = {
      val example = formatter.print(0)
      throw spray.json.DeserializationException(f"'$v' is not a valid date value. Dates must be in this format: '$example'")
    }
  }

  implicit val LabelFormat = jsonFormat6(PivotalLabel)
  implicit val StoryFormat = jsonFormat15(PivotalStory)
  implicit val PersonFormat = jsonFormat5(PivotalPerson)
  implicit val MemberFormat = jsonFormat3(PivotalMember)
  implicit val ItemCreatedFormat = jsonFormat12(PivotalItemCreated)
  implicit val ValidationErrorFormat = jsonFormat2(PivotalValidationError)
  implicit val ErrorFormat = jsonFormat7(PivotalError)
  implicit val IteratonFormat = jsonFormat8(Iteration)
}