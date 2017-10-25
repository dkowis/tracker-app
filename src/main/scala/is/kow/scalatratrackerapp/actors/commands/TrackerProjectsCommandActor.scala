package is.kow.scalatratrackerapp.actors.commands

import akka.actor.{Actor, ActorLogging, Props}
import com.ullink.slack.simpleslackapi.SlackChannel
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import is.kow.scalatratrackerapp.actors.ChannelProjectActor
import is.kow.scalatratrackerapp.actors.ChannelProjectActor.{ChannelQuery, DeregisterChannel, RegisterChannel}
import is.kow.scalatratrackerapp.actors.SlackBotActor.{CommandPrefix, SlackMessage, SlackTyping}

import scala.util.matching.Regex

object TrackerProjectsCommandActor {
  def props(commandPrefix: CommandPrefix) = Props(new TrackerProjectsCommandActor(commandPrefix))
}


class TrackerProjectsCommandActor(commandPrefix: CommandPrefix) extends Actor with ActorLogging {

  private val channelProjectActor = context.actorSelection("/user/channel-project-actor")

  val projectsRegex = "projects(?: +(\\w+))?\\s*.*"

  val projectUrlRegex = "projects\\/(\\d+)"

  //Send typing, and schedule it again a second later!
  def typing(slackChannel: SlackChannel): Unit = {
    context.parent ! SlackTyping(slackChannel)

    import context.dispatcher

    import scala.concurrent.duration._
    //According to the API, every keypress, or in 3 seconds
    context.system.scheduler.scheduleOnce(1.second, self, SlackTyping(slackChannel))
  }


  override def receive: Receive = {
    case smp: SlackMessagePosted =>
      val fullRegex = s"${commandPrefix.prefix}$projectsRegex".r

      smp.getMessageContent match {
        case fullRegex(subCommand) =>
          typing(smp.getChannel)

          log.debug("matched the regex with a capturing group, wrapping it in an option to do stuff")
          //Now I need to figure out what the sub command was, the first group should be the sub command

          Option(subCommand) match {
            case Some(s) if s == "list" =>
              log.info(s"Getting a list of projects for this channel: ${smp.getChannel.getName}(${smp.getChannel.getId})")
            //return the list of projects that are registered to this channel
              channelProjectActor ! ChannelQuery(smp.getChannel)
              context.become(awaitingResponse(smp))

            case Some(s) if s == "remove" =>
              log.info(s"doing a remove for projects: ${smp.getMessageContent}")
            //look for a list of project IDs, or project URLs, to remove from this channel
              try {
                //A pretty naive url parser, but then we can be lazier
                val projectUrlPattern = new Regex(projectUrlRegex)
                val projectIds = smp.getMessageContent.split(" ").drop(3).map{ projectThing =>
                  projectUrlPattern.findFirstIn(projectThing).map { projectId =>
                    projectId.toLong
                  } getOrElse {
                    projectThing.toLong
                  }
                }.toList
                channelProjectActor ! DeregisterChannel(smp.getChannel, projectIds)
                context.become(awaitingResponse(smp))
              } catch {
                case e: Exception =>
                  context.parent ! SlackMessage(
                    channel = smp.getChannel.getId,
                    text = Some(
                      s"""
                         |Sadly I couldn't understand your request, maybe a project ID was not a long?
                         |Error:
                         |`${e.getMessage}`
                      """.stripMargin)
                  )
                  context.stop(self)
              }

            case Some(s) if s == "add" =>
              log.info(s"Doing an add for projects ${smp.getMessageContent}")
            //Look for a list of project IDs, or project URLs, to add to this channel
              try {
                val projectIds = smp.getMessageContent.split(" ").drop(3).map(_.toLong).toList
                channelProjectActor ! RegisterChannel(smp.getChannel, projectIds)
                context.become(awaitingResponse(smp))
              } catch {
                case e: Exception =>
                  context.parent ! SlackMessage(
                    channel = smp.getChannel.getId,
                    text = Some(
                      s"""
                         |Sadly I couldn't understand your request, maybe a project ID was not a long?
                         |Error:
                         |`${e.getMessage}`
                      """.stripMargin)
                  )
                  context.stop(self)
              }
            case _ =>
              //No command specified, or I couldn't recognize it
              context.parent ! SlackMessage(
                channel = smp.getChannel.getId,
                text = Some(
                  """
                    |Usage:
                    |* `projects list` -- displays the projects associated with this channel
                    |* `projects remove [project project ...]` -- remove all of the listed project IDs or URLs from this channel
                    |* `projects add [project project ....]` -- add all of the listed project IDs or URLs to this channel
                  """.stripMargin)
              )
              context.stop(self)
          }
        case _ =>
          log.debug("didn't match registration regex, don't care, nothing to send")
          //TERMINATE
          context.stop(self)
      }
  }

  def awaitingResponse(slackMessagePosted: SlackMessagePosted): Actor.Receive = {
    case cp: ChannelProjectActor.ChannelProject =>
      val channelName = slackMessagePosted.getChannel.getName
      val channelId = slackMessagePosted.getChannel.getId
      val channelText = s"<#$channelId|$channelName>"

      log.debug(s"Received response: $cp")
      if (cp.projectIds.isEmpty) {
        context.parent ! SlackMessage(
          channel = slackMessagePosted.getChannel.getId, //TODO: need to have a way to find the default destination
          text = Some(s"Channel $channelText is not associated with any Tracker Project")
        )
      } else {
        val projectsList = cp.projectIds.map { projectId =>
          s"* https://www.pivotaltracker.com/n/projects/$projectId"
        }

        context.parent ! SlackMessage(
          channel = slackMessagePosted.getChannel.getId,
          text = Some(
            s"""
               |Channel $channelText is associated with the folowing tracker projects:
               |${projectsList.mkString("\n")}
            """.stripMargin)
        )
      }
      log.debug("sent response! I'm done")
      context.stop(self)

    case SlackTyping(channel) =>
      typing(channel)
  }

}
