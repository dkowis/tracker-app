package is.kow.scalatratrackerapp.actors

import akka.actor.{Actor, ActorLogging, Props, Terminated}
import com.ullink.slack.simpleslackapi.SlackUser
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import is.kow.scalatratrackerapp.actors.ChannelProjectActor.{ChannelProject, ChannelQuery}
import is.kow.scalatratrackerapp.actors.QuickChoreCreationActor.QuickCreateChore
import is.kow.scalatratrackerapp.actors.SlackBotActor.{FindUserById, SlackMessage}
import is.kow.scalatratrackerapp.actors.StoryDetailActor.StoryDetailsRequest
import is.kow.scalatratrackerapp.actors.pivotal.PivotalRequestActor.{CreateChore, ListMembers, Members}
import is.kow.scalatratrackerapp.actors.pivotal.{PivotalError, PivotalPerson, PivotalStory}


object QuickChoreCreationActor {
  def props = Props[QuickChoreCreationActor]

  case class QuickCreateChore(smp: SlackMessagePosted, title: String, description: String, assignTo: Option[String] = None, start: Boolean = true)

}

class QuickChoreCreationActor extends Actor with ActorLogging {

  val pivotalRequestActor = context.actorSelection("/user/pivotal-request-actor")
  val channelProjectActor = context.actorSelection("/user/channel-project-actor")
  val parentActor = context.parent

  //Okay, so I'll need *some* details in here, but not a whole lot...
  var assignToUser: Option[SlackUser] = None
  var needAssignUser = false
  var memberList: Option[List[PivotalPerson]] = None
  var qcc: QuickCreateChore = _

  //TODO: this doesn't quite feel right
  lazy val description = if (qcc.description.isEmpty) {
    None
  } else {
    Some(qcc.description)
  }

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

        //Lets also ask for the member list every time, so I can associate people with it
        //We're always going to ask for members
        pivotalRequestActor ! ListMembers(projectId)

        //Need to resolve the slack user, possibly into something I can match on pivotal
        //TODO: need to resolve the slack user first, can't ask the parent for that...
        if (qcc.assignTo.isDefined) {
          parentActor ! FindUserById(qcc.assignTo.get)
          needAssignUser = true
        }
        //start awaiting the user details, including the slack user
        context.become(awaitingUserDetails)
      } getOrElse {
        //We don't have a project ID, so we cannot continue, stopping self.
        //TODO: this is copy pasta from places....
        parentActor ! SlackMessage(
          channel = qcc.smp.getChannel.getId,
          text = Some("I'm sorry, but this channel isn't registered to a project. Use `register <project-id>` to associate it!")
        )
        context.stop(self)
      }
  }

  def awaitingUserDetails: Receive = {
    case Some(slackUser: SlackUser) =>
      log.debug("Got my slack user for who it's assigned to")
      assignToUser = Some(slackUser)
      constructChore()
    case None =>
      //It's gonna be really rare if this ever happens
      log.debug("no slack user was found, return an error, couldn't find slack user to assign to?")
      parentActor ! SlackMessage(
        channel = qcc.smp.getChannel.getId,
        text = Some("I'm sorry I couldn't find the slack user to assign that to... try again? (I don't pick up on line edits yet)")
      )
      context.stop(self)

    case Members(persons) =>
      memberList = Some(persons)
      constructChore()
    case p: PivotalError =>
      //TODO: couldn't get persons, bad things?
      log.error("Unable to get the member list from pivotal tracker!")
      parentActor ! SlackMessage(
        channel = qcc.smp.getChannel.getId,
        text = Some(s"I couldn't get a list of members from the pivotal tracker project: `${p.generalProblem}`")
      )
      context.stop(self)
  }

  def constructChore(): Unit = {
    log.debug(s"\n\tNeeds user: $needAssignUser \n\tMemberList: ${memberList.isDefined} \n\tassignToUser: ${assignToUser.isDefined}")
    if (memberList.isDefined) {
      //I have a members list, I can check for more things
      //Find out the requester correlation
      val requestPivotalPerson = for {
        members <- memberList
        r <- members.find(p => p.email.toLowerCase() == qcc.smp.getSender.getUserMail.toLowerCase())
      } yield {
        r
      }

      val chore:Option[CreateChore] = requestPivotalPerson match {
        case Some(requester) =>
          //Got a requester, can move forward
          if (needAssignUser && assignToUser.isDefined) {
            //we got our assign to user, and we need it
            for {
              members <- memberList
              requestAssign <- assignToUser
              assignment <- members.find(p => p.email.toLowerCase == requestAssign.getUserMail.toLowerCase)
            } yield {
              CreateChore(projectId, qcc.title, Some(assignment.id), requester.id, description, started = true)
            }
          } else {
            //we don't need a user to assign to
            Some(CreateChore(projectId, qcc.title, None, requester.id, description, started = true))
          }
          //If we got here, we don't yet have the assign user, we don't need to do anything yet, except
          // one day respond to a timeout
        case None =>
          //TODO: would need to do something about the test accounts...
          //Different slack would have a different email address
          log.error("unable to correlate user from slack with user on pivotal to assign requestor!")
          parentActor ! SlackMessage(
            channel = qcc.smp.getChannel.getId,
            text = Some(s"I was unable to find a pivotal tracker user to correlate with <@${qcc.smp.getSender.getId}> as chore requester, sorry.")
          )
          context.stop(self)
          None
      }
      //If we've got a chore to send on it's way, send it.
      chore.foreach { c =>
        log.debug(s"Creating chore: $chore")
        pivotalRequestActor ! c
        context.become(awaitingPivotalConfirmation)
      }
    } //otherwise we haven't gotten a member list, and we can't do anything with that

  }

  def awaitingPivotalConfirmation: Receive = {
    case p: PivotalStory =>
      log.debug("Received my pivotal story, that means it was successful, time to send a story destails request")

      val detailActor = context.actorOf(StoryDetailActor.props)
      context.watch(detailActor)

      detailActor ! StoryDetailsRequest(qcc.smp, Right(p))

      context.become(awaitingChildDeath)

    //TODO: this needs to be so much prettier
    case pivotalError: PivotalError =>
      parentActor ! SlackMessage(
        channel = qcc.smp.getChannel.getId,
        text = Some(s"Unable to create chore. Error `${pivotalError.error}` General Problem: `${pivotalError.generalProblem}`")
      )
      context.stop(self)

    case x@_ =>
      log.error(s"Something real bad happened trying to create a quick chore: $x")
      parentActor ! SlackMessage(
        channel = qcc.smp.getChannel.getId,
        text = Some(s"Things didn't go as I planned, share this with my owner: `$x`")
      )
      context.stop(self)
  }

  def awaitingChildDeath: Receive = {
    case Terminated(x) =>
      log.debug("child is done, time to go down!")
      context.stop(self)
  }
}
