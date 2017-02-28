package is.kow.scalatratrackerapp

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import slick.jdbc.JdbcBackend

/**
  * Set up my persistence stuff on JVM load -- I AM AN ANTIPATTERN! :(
  * TODO: get this into an actor that potentially spawns a connection pool actor, or becomes it!
  * TODO: probably not the most robust way to do this, but it works for now
  * I don't think I need to dependency inject this...
  */
object Persistence {
  //Set up a hikari CP thingy
  val hikariConfig = new HikariConfig()

  hikariConfig.setJdbcUrl(AppConfig.dbUrl)
  hikariConfig.setUsername(AppConfig.dbUser)
  hikariConfig.setPassword(AppConfig.dbPass)
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

  //Set up slick -- http://slick.lightbend.com/doc/3.1.0/database.html#using-a-datasource
  val db = JdbcBackend.Database.forDataSource(dataSource)
}
