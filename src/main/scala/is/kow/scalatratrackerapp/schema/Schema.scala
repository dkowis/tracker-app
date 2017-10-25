package is.kow.scalatratrackerapp.schema

/**
  * http://slick.lightbend.com/doc/3.1.1/gettingstarted.html#schema
  */
object Schema {
  import slick.jdbc.MySQLProfile.api._

  class ChannelProjects(tag: Tag) extends Table[(String, Long)](tag, "channel_projects") {
    def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
    def channelId = column[String]("channel_id")
    def projectId = column[Long]("project_id")
    def *  = (channelId, projectId)
  }
  val channelProjects = TableQuery[ChannelProjects]
}
