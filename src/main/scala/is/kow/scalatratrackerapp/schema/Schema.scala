package is.kow.scalatratrackerapp.schema

/**
  * http://slick.lightbend.com/doc/3.1.1/gettingstarted.html#schema
  */
object Schema {
  import slick.driver.MySQLDriver.api._

  class ChannelProjects(tag: Tag) extends Table[(String, Long)](tag, "channel_projects") {
    def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
    def channelName = column[String]("channel_name")
    def projectId = column[Long]("project_id")
    def *  = (channelName, projectId)
  }
  val channelProjects = TableQuery[ChannelProjects]
}
