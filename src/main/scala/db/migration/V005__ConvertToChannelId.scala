package db.migration

import java.sql.Connection

import is.kow.scalatratrackerapp.TrackerBot
import org.flywaydb.core.api.migration.jdbc.JdbcMigration
import org.slf4j.LoggerFactory
import akka.pattern.ask
import akka.util.Timeout
import com.ullink.slack.simpleslackapi.SlackChannel
import is.kow.scalatratrackerapp.actors.SlackBotActor.FindChannelByName

import scala.concurrent.Await
import scala.concurrent.duration._

class V005__ConvertToChannelId extends JdbcMigration {
  val log = LoggerFactory.getLogger(this.getClass)

  override def migrate(connection: Connection) = {
    log.info("Starting to execute java migration!")
    val queryStatement = connection.prepareStatement("select id, channel_name from channel_projects")
    val updateStatement = connection.prepareStatement("update channel_projects set channel_id = ? where id = ?")

    val slackBotActor = TrackerBot.slackBotActor
    implicit val timeout = Timeout(5 seconds)

    try {
      val results = queryStatement.executeQuery()
      log.info("Queried all channels")

      try {
        //Go through all the results and do another insert each time
        while(results.next()) {
          val channelName = results.getString("channel_name")
          val rowId = results.getInt("id")
          log.info(s"Getting channel ID for ${channelName}")
          //Ask the slackbot actor for the channel ID for a channel
          val optionalResult = Await.result((slackBotActor ? FindChannelByName(channelName)).mapTo[Option[SlackChannel]], timeout.duration)
          if(optionalResult.isDefined) {
            log.info(s"Acquired channel ID ${optionalResult.get.getId} for ${channelName}")
            updateStatement.setString(1, optionalResult.get.getId)
            updateStatement.setInt(2, rowId)
            updateStatement.execute()
            log.info(s"Updated ${optionalResult.get.getId} for rowid ${rowId} (${channelName})")
          } else {
            //Need to drop that row
          }
        }
      } finally {
        results.close()
        updateStatement.close()
      }
    } finally {
      queryStatement.close()
    }
  }

}
