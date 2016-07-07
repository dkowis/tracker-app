package is.kow.scalatratrackerapp

import java.io.File

import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import org.asynchttpclient.AsyncHttpClientConfig
import play.api.libs.ws.ahc.{AhcConfigBuilder, AhcWSClient, AhcWSClientConfig}

/**
  * Set up a play-ws client for use
  */
object MyWSClient {
  private implicit val actorSystem = AppConfig.system
  private implicit val materializer = ActorMaterializer()

  import play.api._
  import play.api.libs.ws._

  //TODO: this should probably load from my configuration file
  private val configuration = Configuration.reference ++ Configuration(ConfigFactory.parseString(
    """
      |play.ws.followRedirects = true
    """.stripMargin))

  // If running in Play, environment should be injected
  private val environment = Environment(new File("."), this.getClass.getClassLoader, Mode.Prod)

  private val parser = new WSConfigParser(configuration, environment)
  private val config = new AhcWSClientConfig(wsClientConfig = parser.parse())
  private val builder = new AhcConfigBuilder(config)
  private val logging = new AsyncHttpClientConfig.AdditionalChannelInitializer() {
    override def initChannel(channel: io.netty.channel.Channel): Unit = {
      channel.pipeline.addFirst("log", new io.netty.handler.logging.LoggingHandler("debug"))
    }
  }
  private val ahcBuilder = builder.configure()
  ahcBuilder.setHttpAdditionalChannelInitializer(logging)
  private val ahcConfig = ahcBuilder.build()

  val wsClient = new AhcWSClient(ahcConfig)
}
