package is.kow.scalatratrackerapp

import akka.event.slf4j.SLF4JLogging
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import slick.jdbc.JdbcBackend

case class DatabaseCreds(
                          hostname: String, port: Int, username: String, password: String, databaseName: String,
                          uri: Option[String] = None,
                          jdbcUrl: Option[String] = None,
                          caCert: Option[String] = None,
                          clientCert: Option[String] = None,
                          clientKey: Option[String] = None,
                          instanceName: Option[String] = None
                        ) {
}

/**
  * Set up my persistence stuff on JVM load -- I AM AN ANTIPATTERN! :(
  * TODO: get this into an actor that potentially spawns a connection pool actor, or becomes it!
  * TODO: probably not the most robust way to do this, but it works for now
  * I don't think I need to dependency inject this...
  */
object Persistence extends SLF4JLogging {

  //Set up a hikari CP thingy
  val hikariConfig = new HikariConfig()

  private val creds = AppConfig.dbCreds

//  if (creds.jdbcUrl.isDefined) {
//    hikariConfig.setJdbcUrl(creds.jdbcUrl.get)
//  } else if (creds.uri.isDefined) {
//    hikariConfig.setJdbcUrl(s"jdbc:${creds.uri.get}")
//  } else {
    val jdbcUrl = s"jdbc:mysql://${creds.hostname}:${creds.port}/${creds.databaseName}"
    hikariConfig.setJdbcUrl(jdbcUrl)
//  }

  log.error(s"jdbcUrl: ${hikariConfig.getJdbcUrl}")

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

  hikariConfig.setMaximumPoolSize(5) // the free account can't handle many connections *AT ALL* 4 Max

  //Connection suggestions based on cloud foundry suggestions
  hikariConfig.setConnectionTimeout(15000)
  hikariConfig.setIdleTimeout(60000)
  hikariConfig.setMinimumIdle(3)
  hikariConfig.setLeakDetectionThreshold(2000)

  //TODO: how do I get this out to be used by the other stuff
  val dataSource = new HikariDataSource(hikariConfig)

  //Handle flyway migrations and configuration stuff
  //Application will fail to start if flyway wasn't able to migrate
  val flyway = new Flyway()
  flyway.setDataSource(dataSource)
  try {
    flyway.migrate()
  } catch {
    case e: FlywayException =>
      //Just repair the metadata and hork on out
      flyway.repair()
      throw e;
  }

  //http://slick.lightbend.com/doc/3.2.0/database.html#using-a-datasource
  //The data pool max size now needs to be specified
  val db = JdbcBackend.Database.forDataSource(dataSource, Some(5))
}
