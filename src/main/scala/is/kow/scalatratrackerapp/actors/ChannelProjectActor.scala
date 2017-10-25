package is.kow.scalatratrackerapp.actors


import java.util.concurrent.Executors

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.dispatch.ExecutionContexts
import com.ullink.slack.simpleslackapi.SlackChannel
import is.kow.scalatratrackerapp.actors.persistence.PersistenceActor.GetJdbcBackend
import is.kow.scalatratrackerapp.schema.Schema
import nl.grons.metrics.scala.DefaultInstrumented
import slick.jdbc.JdbcBackend

import scala.concurrent.Future
import scala.util.{Failure, Success}

object ChannelProjectActor {

  def props(persistenceActor: ActorRef) = Props(new ChannelProjectActor(persistenceActor))

  case class RegisterChannel(channel: SlackChannel, projectIds: List[Long])

  case class DeregisterChannel(channel: SlackChannel, projectIds: List[Long])

  case class ChannelProject(channel: SlackChannel, projectIds: List[Long])

  case class ChannelQuery(channel: SlackChannel)

}

class ChannelProjectActor(persistenceActor: ActorRef) extends Actor with ActorLogging with DefaultInstrumented {

  import ChannelProjectActor._
  import slick.jdbc.MySQLProfile.api._

  //TODO: I don't know if this number needs to match the database pool size, or the JDBC backend size...
  implicit val queryContext = ExecutionContexts.fromExecutorService(Executors.newFixedThreadPool(5))

  private val channelQueryTimer = metrics.timer("db.channel_query")
  private val channelRegisterTimer = metrics.timer("db.channel_register")
  private val channelDeregisterTimer = metrics.timer("db.channel_deregister")

  //Ask the persistence actor for our JDBC backend
  //TODO: should probably have a failure timeout or something
  persistenceActor ! GetJdbcBackend

  def receive: Receive = {
    case backend: JdbcBackend.DatabaseDef =>
      log.info("Received Database Connection, becoming ready to serve!")
      context.become(connectedToDb(backend))
  }

  import Schema._

  def connectedToDb(db: JdbcBackend.DatabaseDef): Receive = {
    case ChannelQuery(channel) =>
      val sender = context.sender()
      log.debug("receiving a channel query")
      //Apparently I actually stored the channel ID with the thing not the name, although I can fix that
      //TODO: this should probably have some kind of failure catching?
      projectsForChannel(db, channel).map { result =>
        sender ! ChannelProject(channel, result)
      }

    case RegisterChannel(channel, projectIds) =>
      val sender = context.sender()
      log.debug("Received a registration request!")

      projectsForChannel(db, channel).map { registeredProjects =>
        //Got the list of registered projects, now dedupe it, and insert all the new ones
        val toInsert = projectIds.filterNot(p => registeredProjects.contains(p)).map { projectId =>
          (channel.getId, projectId)
        }

        //Insert a row for each toRegister
        val actions = channelProjects ++= toInsert
        val asyncResult = channelRegisterTimer.timeFuture(db.run(actions))(queryContext)

        asyncResult.onComplete {
          case Success(r) =>
            r.map { rows =>
              log.info(s"$rows inserted!")
            }
            log.info(s"Successful insert of new projects")

            projectsForChannel(db, channel).map { result =>
              sender ! ChannelProject(channel, result)
            }

          case Failure(r) =>
            log.error(s"Unable to update projects for ${channel.getName()}(${channel.getId}): ${r.getMessage}")
          //TODO: don't have any message to report back
        }
      }

    case DeregisterChannel(channel, projectIds) =>
      val sender = context.sender()
      val dropQuery = channelProjects.filter(_.channelId === channel.getId).filter(_.projectId.inSetBind(projectIds))
        .delete

      val asyncResult = channelDeregisterTimer.timeFuture(db.run(dropQuery))(queryContext)

      asyncResult.onComplete {
        case Success(r) =>
          log.info(s"Deregistered ${projectIds.mkString(", ")} from ${channel.getName}(${channel.getId})")
          projectsForChannel(db, channel).map { result =>
            sender ! ChannelProject(channel, result)
          }
        case Failure(r) =>
          log.error(s"Unable to deregister ${projectIds.mkString(", ")} from ${channel.getName}(${channel.getId})")
        //TODO: need some way to report back
      }
  }

  /**
    * Used ina  bunch of places in here, just want to get the projects for the specified channel
    * @param db jdbc backend
    * @param channel slack channel
    * @return A future of those projects
    */
  def projectsForChannel(db: JdbcBackend.DatabaseDef, channel: SlackChannel): Future[List[Long]] = {
    val q = channelProjects.filter(_.channelId === channel.getId)
    val asyncResult = channelQueryTimer.timeFuture(db.run(q.result))(queryContext)
    asyncResult.map { r =>
      if (r.isEmpty) {
        log.debug(s"No projects associated with ${channel.getName}")
        List.empty[Long]
      }
      else {
        val projectIds: List[Long] = r.map(_._2).toList
        log.debug(s"Projects ${projectIds.mkString(", ")} associated with ${channel.getName}(${channel.getId}")
        //Should get a list of projects
        projectIds
      }
    }(queryContext)
  }
}
