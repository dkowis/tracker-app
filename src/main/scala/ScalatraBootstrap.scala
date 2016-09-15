import javax.servlet.ServletContext

import is.kow.scalatratrackerapp.actors.{ChannelProjectActor, PivotalRequestActor, SlackBotActor}
import is.kow.scalatratrackerapp.{AppConfig, Persistence, TrackerAppServlet}
import org.scalatra._

//TODO: cannot make this a dependency injected thing....
class ScalatraBootstrap extends LifeCycle {
  override def init(context: ServletContext) {
    context.mount(new TrackerAppServlet, "/*")

    Persistence.db

    val system = AppConfig.system

    //Start up the singleton actors we need
    system.actorOf(PivotalRequestActor.props, "pivotal-request-actor")
    system.actorOf(ChannelProjectActor.props, "channel-project-actor")

    system.actorOf(SlackBotActor.props, "slack-bot-actor")

    //This is where I should start any other actors, probably ones that would register commands and what not

  }

  override def destroy(context: ServletContext): Unit = {
    //TODO: how can I hook this non-injected thing into the right spot?
    //ActorSetup.terminate()
  }
}
