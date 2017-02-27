package is.kow.scalatratrackerapp.actors

import akka.actor.{Actor, ActorLogging, Props}
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import is.kow.scalatratrackerapp.actors.SlackBotActor.CommandPrefix
import is.kow.scalatratrackerapp.actors.commands.{QuickChoreCommandActor, TrackerRegistrationCommandActor}
import is.kow.scalatratrackerapp.actors.responders.TrackerStoryPatternActor

object MessageProcessingActor {
  def props(commandPrefix: CommandPrefix) = Props(new MessageProcessingActor(commandPrefix))
}


class MessageProcessingActor(commandPrefix: CommandPrefix) extends Actor with ActorLogging {

  //Available commands:
  val allCommands = List(
    QuickChoreCommandActor,
    TrackerRegistrationCommandActor
  )

  //Available message matchers:
  val allMatchers = List(
    TrackerStoryPatternActor
  )

  override def receive: Receive = {
    case smp: SlackMessagePosted =>
    //Got a slack message, now we need to go match it through all teh things, commands first I guess
    //Or shotgun it to all of them? and they die of their own accord?
    //shotgun seems nice, a graceful termination is probably okay, mark sweep should handle the garbage fine
  }
}