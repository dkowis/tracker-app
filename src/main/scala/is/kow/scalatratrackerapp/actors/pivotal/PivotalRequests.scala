package is.kow.scalatratrackerapp.actors.pivotal

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


object PivotalRequestFormat extends SnakifiedJsonSupport {
  implicit val pivotalStoryCreationFormat = jsonFormat7(PivotalStoryCreation)
}