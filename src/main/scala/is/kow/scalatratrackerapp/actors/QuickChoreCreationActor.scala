package is.kow.scalatratrackerapp.actors

import akka.actor.{Actor, ActorLogging, Props}


object QuickChoreCreationActor {
  def props = Props[QuickChoreCreationActor]
}

class QuickChoreCreationActor extends Actor with ActorLogging {

  val pivotalRequestActor = context.actorSelection("/user/pivotal-request-actor")
  val channelProjectActor = context.actorSelection("/user/channel-project-actor")
  val slackBotActor = context.actorSelection("/user/slack-bot-actor")

  //Okay, so I'll need *some* details in here, but not a whole lot...

  override def receive: Receive = {
    case _ =>
      //bleh
  }
}
