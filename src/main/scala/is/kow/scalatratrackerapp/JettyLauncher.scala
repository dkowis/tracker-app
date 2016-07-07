package is.kow.scalatratrackerapp

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.webapp.WebAppContext
import org.scalatra.servlet.ScalatraListener

/**
  * Created by dxk0875 on 7/7/16.
  */
object JettyLauncher extends App{
  val port = AppConfig.config.getInt("port")

  val server = new Server(port)
  val context = new WebAppContext()
  context.setContextPath("/")

  context.setResourceBase("src/main/webapp")

  context.setEventListeners(Array(new ScalatraListener()))

  server.setHandler(context)

  //TODO: Do I have to add my own context mounting here? or will bootstrap work?

  server.start()
  server.join()
}
