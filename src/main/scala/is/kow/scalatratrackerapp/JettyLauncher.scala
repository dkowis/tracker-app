package is.kow.scalatratrackerapp

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.webapp.WebAppContext
import org.scalatra.servlet.ScalatraListener

/**
  * Just a launcher for jetty to be used in cloud foundry
  */
object JettyLauncher extends App{
  val port = AppConfig.config.getInt("port")

  val server = new Server(port)
  val context = new WebAppContext()
  context.setContextPath("/")

  context.setResourceBase("src/main/webapp")

  context.setEventListeners(Array(new ScalatraListener()))

  server.setHandler(context)

  server.start()
  server.join()
}
