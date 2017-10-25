package is.kow.scalatratrackerapp.actors.commands

import akka.actor.{Actor, ActorLogging, Props}
import com.ullink.slack.simpleslackapi.SlackChannel
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import is.kow.scalatratrackerapp.actors.SlackBotActor.{CommandPrefix, SlackMessage, SlackTyping}

object TrackerProjectsCommandActor {
  def props(commandPrefix: CommandPrefix) = Props(new TrackerProjectsCommandActor(commandPrefix))
}


class TrackerProjectsCommandActor(commandPrefix: CommandPrefix) extends Actor with ActorLogging {
  //TODO: add a regex for de-registering a channel

  val projectsRegex = "projects(?: +(\\w+))?\\s*"

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
            case Some("list") =>
            //return the list of projects that are registered to this channel
            case Some("remove") =>
            //look for a list of project IDs, or project URLs, to remove from this channel
            case Some("add") =>
            //Look for a list of project IDs, or project URLs, to add to this channel
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

  def awaitingRegistrationResponse: Receive = {
    case slackMessage: SlackMessage =>
      context.parent ! slackMessage
      context.stop(self)

    case SlackTyping(channel) =>
      typing(channel)
  }

}
