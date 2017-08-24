package is.kow.scalatratrackerapp.actors


import akka.actor.{Actor, ActorLogging, Props}
import com.ullink.slack.simpleslackapi.SlackChannel
import is.kow.scalatratrackerapp.Persistence
import is.kow.scalatratrackerapp.schema.Schema
import nl.grons.metrics.scala.DefaultInstrumented

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object ChannelProjectActor {

  def props = Props[ChannelProjectActor]

  //Register some number of ProjectIds with this channel
  case class RegisterProjects(channel: SlackChannel, projectIds: List[Long])

  //What projects are associated with this channel?
  case class ChannelQuery(channel: SlackChannel)

  //A list of project IDs to remove from this channel
  case class DeregisterProjects(channel: SlackChannel, projectIds: List[Long])

  //Oh I remember why I needed these, so that I can do all my database access in here
  case class ChannelProjects(channel: SlackChannel, projectIds: List[Long])

  //For when, for whatever reason, we time out asking for this
  case object ChannelProjectTimeout

}

class ChannelProjectActor extends Actor with ActorLogging with DefaultInstrumented {

  import ChannelProjectActor._
  import slick.jdbc.MySQLProfile.api._

  private val db = Persistence.db

  private implicit val executionContext = context.dispatcher

  private val channelQueryTimer = metrics.timer("db.channel_query")
  private val channelRegisterTimer = metrics.timer("db.channel_register")

  import Schema._

  def receive = {
    case ChannelQuery(channel) =>
      val sender = context.sender()
      log.debug("receiving a channel query")

      queryProjectsForChannel(channel.getName).onComplete{
        case Success(resultantIds) =>
          sender ! ChannelProjects(channel, resultantIds)
        case Failure(r) =>
          log.error(s"Unable to get projects for ${channel.getName}: ${r.getMessage}")
      }

    case RegisterProjects(channel, projectIds) =>
      val sender = context.sender()
      //Determine what projects, if any, are associated with this channel

      addProjectToChannel(channel.getName, projectIds).onComplete {
        case Success(resultantIds) =>
          sender ! ChannelProjects(channel, resultantIds)
        case Failure(r) =>
          log.error(s"Unable to register projects for ${channel.getName}: ${r.getMessage}")
          //TODO: pass back a failure instead of silently eating it
      }

    case DeregisterProjects(channel, projectIds) =>
      val sender = context.sender()
      removeProjectFromChannel(channel.getName, projectIds).onComplete{
        case Success(resultantIds) =>
          sender ! ChannelProjects(channel, resultantIds)
        case Failure(r) =>
          log.error(s"Unable to deregister projects from ${channel.getName}: ${r.getMessage}")
          //TODO: pass back the error, instead of eating it.
      }
  }

  //Retreive a list of projects for a given channel name
  def queryProjectsForChannel(channelName: String): Future[List[Long]] = {
    val q = channelProjects.filter(_.channelName === channelName)
    val asyncResult = channelQueryTimer.timeFuture(db.run(q.result))

    //Transform it asynchronously and return the future of the right thing
    asyncResult.map { result =>
      result.seq.map { case (channelName, project) => project }.toList
    }
  }

  //add the project to the channel, and then return the list of projects associated with this channel
  def addProjectToChannel(channelName: String, projectIds: List[Long]): Future[List[Long]] = {
    //Insert another row for each one of the projectIds in the list
    val thing: Seq[(String, Long)] = projectIds.map(pid => (channelName, pid))
    //Insert all the rows!
    val q = channelProjects ++= thing
    val result = db.run(q)

    result.flatMap { r =>
      queryProjectsForChannel(channelName).map { ids =>
        ids
      }
    }
  }

  def removeProjectFromChannel(channelName: String, projectIds: List[Long]): Future[List[Long]] = {
    val thing: Seq[(String, Long)] = projectIds.map(pid => (channelName, pid))
    //Delete the rows that are specified
    val q = channelProjects.filter { cp =>
      cp.channelName === channelName
    }.filter{ cp =>
      cp.projectId inSetBind projectIds
    }
    val action = q.delete

    val result = db.run(action)
    result.flatMap { r =>
      queryProjectsForChannel(channelName).map {ids =>
        ids
      }
    }
  }
}
