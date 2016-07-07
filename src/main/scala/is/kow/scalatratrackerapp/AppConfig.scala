package is.kow.scalatratrackerapp

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

object AppConfig {

  //Load up the config from the config file, and then use it
  val config = ConfigFactory.load("application")

  val vcapApplication = ConfigFactory.parseString(config.getString("vcap_application"))

  val vcapServices = ConfigFactory.parseString(config.getString("vcap_services"))

  private val dbRoot = vcapServices.getConfigList("p-mysql").get(0).getConfig("credentials")

  val dbUrl = dbRoot.getString("jdbcUrl")
  val dbUser = dbRoot.getString("username")
  val dbPass = dbRoot.getString("password")


  //I want a singleton actor system, so just stick it in here
  val system = ActorSystem.create("myActorSystem")
}
