package is.kow.scalatratrackerapp.actors


import akka.actor.{Actor, ActorLogging, Props}
import com.ullink.slack.simpleslackapi.SlackChannel
import is.kow.scalatratrackerapp.Persistence
import is.kow.scalatratrackerapp.schema.Schema

import scala.util.{Failure, Success}

object ChannelProjectActor {

  def props = Props[ChannelProjectActor]

  case class RegisterChannel(channel: SlackChannel, projectId: Long)

  case class ChannelQuery(channel: SlackChannel)

  case class DeregisterChannel(channel: SlackChannel)

  //Oh I remember why I needed these, so that I can do all my database access in here
  case class ChannelProject(channel: SlackChannel, projectId: Option[Long])

}

class ChannelProjectActor extends Actor with ActorLogging {

  import ChannelProjectActor._
  import slick.driver.MySQLDriver.api._

  val db = Persistence.db

  implicit val executionContext = context.dispatcher

  import Schema._

  def receive = {
    case ChannelQuery(channel) =>
      val sender = context.sender()
      log.debug("receiving a channel query")
      //Apparently I actually stored the channel ID with the thing not the name, although I can fix that
      val q = channelProjects.filter(_.channelName === channel.getName)
      val asyncResult = db.run(q.result)
      asyncResult.map { r =>
        if (r.isEmpty) {
          log.debug(s"No project associated with ${channel.getName}")
          sender ! ChannelProject(channel, None)
        } else {
          log.debug(s"Project ${r.head._2} associated with ${channel.getName}")
          sender ! ChannelProject(channel, Some(r.head._2))
        }
      }

    case RegisterChannel(channel, projectId) =>
      val sender = context.sender()
      log.debug("Received a registration request!")

      val updated = channelProjects.insertOrUpdate((channel.getName, projectId)).asTry
      //DERP: have to run the thing against the database
      val asyncResult = db.run(updated)
      //Handle the future of this channel
      asyncResult.map {
        case Success(r) =>
          log.debug(s"Updated channel ${channel.getName} to $projectId. rows updated: $r")
          sender ! ChannelProject(channel, Some(projectId))
        case Failure(r) =>
          //TODO: FAILURE UPDATING OR INSERTING NEED A FAILURE ACTOR
          log.error(s"Unable to insert/update for ${channel.getName} to $projectId: FAILURE: $r ")
          log.error(r.getStackTrace.mkString("\n\t"))
      }

    case DeregisterChannel(channel) =>
    //TODO: drop the channel
  }
}
