import javax.servlet.ServletContext

import is.kow.scalatratrackerapp.actors.{ChannelProjectActor, PivotalRequestActor, SlackBotActor, SlackRequestActor}
import is.kow.scalatratrackerapp.{AppConfig, Persistence, TrackerAppServlet}
import org.scalatra._

//TODO: cannot make this a dependency injected thing....
class ScalatraBootstrap extends LifeCycle {
  override def init(context: ServletContext) {
    context.mount(new TrackerAppServlet, "/*")

    Persistence.db

    val system = AppConfig.system

    //Start up the singleton actors we need
    system.actorOf(SlackRequestActor.props, "slack-request-actor")
    system.actorOf(PivotalRequestActor.props, "pivotal-request-actor")
    system.actorOf(ChannelProjectActor.props, "channel-project-actor")

    system.actorOf(SlackBotActor.props, "slack-bot-actor")


  }

  override def destroy(context: ServletContext): Unit = {
    //TODO: how can I hook this non-injected thing into the right spot?
    //ActorSetup.terminate()
  }
}
