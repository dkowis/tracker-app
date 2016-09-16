package is.kow.scalatratrackerapp.actors

import akka.actor.{Actor, ActorLogging, Props}
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import com.ullink.slack.simpleslackapi.{SlackChannel, SlackUser}
import is.kow.scalatratrackerapp.actors.ChannelProjectActor.{ChannelProject, ChannelQuery}
import is.kow.scalatratrackerapp.actors.QuickChoreCreationActor.QuickCreateChore
import is.kow.scalatratrackerapp.actors.SlackBotActor.{FindUserById, SlackMessage}
import is.kow.scalatratrackerapp.actors.StoryDetailActor.StoryDetailsRequest
import is.kow.scalatratrackerapp.actors.pivotal.{PivotalItemCreated, PivotalPerson, PivotalStory}
import is.kow.scalatratrackerapp.actors.pivotal.PivotalRequestActor.{CreateChore, ListMembers, Members}


object QuickChoreCreationActor {
  def props = Props[QuickChoreCreationActor]

  case class QuickCreateChore(smp: SlackMessagePosted, title: String, description: String, assignTo: Option[String] = None)

}

class QuickChoreCreationActor extends Actor with ActorLogging {

  val pivotalRequestActor = context.actorSelection("/user/pivotal-request-actor")
  val channelProjectActor = context.actorSelection("/user/channel-project-actor")
  val slackBotActor = context.actorSelection("/user/slack-bot-actor")

  //Okay, so I'll need *some* details in here, but not a whole lot...
  var user: SlackUser = _
  var qcc: QuickCreateChore = _
  var projectId: Long = _

  override def receive: Receive = {
    case qcc: QuickCreateChore =>
      this.qcc = qcc

      //Need to ask for the project ID every time no matter what
      channelProjectActor ! ChannelQuery(qcc.smp.getChannel)
      context.become(awaitingProjectId)
  }

  def awaitingProjectId: Receive = {
    case p: ChannelProject =>
      p.projectId.map { pid =>
        //I have to ask for user member list now
        projectId = pid

        //Need to resolve the slack user, possibly into something I can match on pivotal
        if (qcc.assignTo.isDefined) {
          slackBotActor ! FindUserById(qcc.assignTo.get)
          context.become(awaitingUserDetails)
        } else {
          //TODO: this is copypasta!
          //I don't need to wait for it, I can just do work, create the ticket
          val description = if (qcc.description.isEmpty) {
            None
          } else {
            Some(qcc.description)
          }
          pivotalRequestActor ! CreateChore(projectId, qcc.title, None, description)
          context.become(awaitingPivotalConfirmation)
        }
      } getOrElse {
        //We don't have a project ID, so we cannot continue, stopping self.
        //TODO: this is copy pasta from places....
        slackBotActor ! SlackMessage(
          channel = qcc.smp.getChannel.getId,
          text = Some("I'm sorry, but this channel isn't registered to a project. Use `register <project-id>` to associate it!")
        )
        context.stop(self)
      }
  }

  def awaitingUserDetails: Receive = {
    case Some(slackUser: SlackUser) =>
      //ask for channel project ID, and then ask for members
      user = slackUser
      pivotalRequestActor ! ListMembers(projectId)

      context.become(awaitingMemberList)
    case None =>
      //It's gonna be really rare if this ever happens
      log.debug("no slack user was found, return an error, couldn't find slack user to assign to?")
      slackBotActor ! SlackMessage(
        channel = qcc.smp.getChannel.getId,
        text = Some("I'm sorry I couldn't find the slack user to assign that to... try again? (I don't pick up on line edits yet)")
      )
      context.stop(self)
  }


  def awaitingMemberList: Receive = {
    case Members(persons) =>
      //TODO: need to also grab the user requesting the creation to assign that
      persons.find(p => p.email == user.getUserMail) match {
        case Some(person) =>
          //found our user, send another request to pivotal request actor
          val description = if (qcc.description.isEmpty) {
            None
          } else {
            Some(qcc.description)
          }

          val createChore = CreateChore(projectId, qcc.title, Some(person.id), description)
          log.debug(s"Trying to create chore: $createChore")
          pivotalRequestActor ! createChore
          context.become(awaitingPivotalConfirmation)
        case None =>
          //Couldn't find it, bail I guess?
          log.error("Unable to map Slack user to pivotal user")
          slackBotActor ! SlackMessage(
            channel = qcc.smp.getChannel.getId,
            text = Some(s"Unfortunately I couldn't map <@${user.getId}> to a pivotal tracker user to assign them :(")
          )
          context.stop(self)
      }
  }

  def awaitingPivotalConfirmation: Receive = {
    case p: PivotalStory =>
      log.debug("Received my pivotal story, that means it was successful, time to send a story destails request")
      //TODO: need to put it into the current iteration as well, I don't think I can do that
      //TODO: I can put it at the top of the backlog perhaps. Would have to ask for the latest iteration, and then
      // put the story before the right spot. Should probably query that prior to creating the story, so it's just created
      // at the right spot

      slackBotActor ! SlackMessage(
        channel = qcc.smp.getChannel.getId,
        text = Some(s"Chore created: ${p.url}")
      )

      //aaand I'm out
      context.stop(self)

    case failure =>
      //TODO: wasn't able to create the story, should report that back to slack
      log.error("Unable to create the chore!")

      context.stop(self)
  }
}
