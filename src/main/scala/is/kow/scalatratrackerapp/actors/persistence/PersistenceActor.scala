package is.kow.scalatratrackerapp.actors.persistence

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import is.kow.scalatratrackerapp.actors.persistence.PersistenceActor.GetJdbcBackend
import nl.grons.metrics.scala.DefaultInstrumented
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import slick.jdbc.JdbcBackend

object PersistenceActor {
  def props(slackBotActor: ActorRef, creds: DatabaseCreds) = Props(new PersistenceActor(slackBotActor, creds))

  case object GetJdbcBackend

}

class PersistenceActor(slackBotActor: ActorRef, creds: DatabaseCreds) extends Actor with ActorLogging with DefaultInstrumented {

  //Use this to define the size of the datasource pool max size as well as the thread pool size for executing that work
  private val POOL_SIZE = 5

  private val hikariConfig = new HikariConfig()

  private val jdbcUrl = s"jdbc:mysql://${creds.hostname}:${creds.port}/${creds.databaseName}"
  hikariConfig.setJdbcUrl(jdbcUrl)

  log.debug(s"JDBC URL: ${hikariConfig.getJdbcUrl}")

  hikariConfig.setUsername(creds.username)
  hikariConfig.setPassword(creds.password)

  //If we have properties, set them!
  if (creds.caCert.isDefined) {
    log.error("Setting up SSL socket connection")
    hikariConfig.addDataSourceProperty("socketFactory", "com.homedepot.cloudfoundry.googlecloudsql.mysql.SocketFactory")
    hikariConfig.addDataSourceProperty("caCert", creds.caCert.get)
    hikariConfig.addDataSourceProperty("clientCert", creds.clientCert.get)
    hikariConfig.addDataSourceProperty("clientKey", creds.clientKey.get)
    hikariConfig.addDataSourceProperty("instanceName", creds.instanceName.get)
    hikariConfig.addDataSourceProperty("useSSL", "true")
  }

  hikariConfig.setMaximumPoolSize(POOL_SIZE) //Trackerbot isn't super busy 5 concurrent requests is probably good enough

  //TODO: I don't know if these are valuable any more, since i'm not on the PCF foundation any more
  hikariConfig.setConnectionTimeout(15000)
  hikariConfig.setIdleTimeout(60000)
  hikariConfig.setMinimumIdle(3)
  hikariConfig.setLeakDetectionThreshold(2000)

  val dataSource = new HikariDataSource(hikariConfig)

  //Handle flyway migrations and configuration stuff
  //Application will fail to start if flyway wasn't able to migrate
  private val flyway = new Flyway()
  flyway.setDataSource(dataSource)
  try {
    log.info("About to run migrations")
    flyway.migrate()
    log.info("Migrations complete")
  } catch {
    case e: FlywayException =>
      //Just repair the metadata and hork on out
      flyway.repair()
      throw e;
  }


  //http://slick.lightbend.com/doc/3.2.0/database.html#using-a-datasource
  //The data pool max size now needs to be specified
  private val db = JdbcBackend.Database.forDataSource(dataSource, Some(POOL_SIZE))

  override def receive = {
    case GetJdbcBackend =>
      log.debug("Respond with the jdbc backend object")
      sender ! db
  }
}
