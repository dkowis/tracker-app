import javax.servlet.ServletContext

import is.kow.scalatratrackerapp.TrackerAppServlet
import org.scalatra._

class ScalatraBootstrap extends LifeCycle {
  override def init(context: ServletContext) {
    context.mount(new TrackerAppServlet, "/*")

    //TODO: this works for now, but is kinda gross.
    Persistence.db
  }
}
