package is.kow.scalatratrackerapp

import akka.event.slf4j.SLF4JLogging
import com.typesafe.config.{Config, ConfigFactory}
import is.kow.scalatratrackerapp.config.VcapService
import spray.json.JsonParser


object AppConfig extends SLF4JLogging {

  //Load up the config from the config file, and then use it
  val config = ConfigFactory.load("application")

  val vcapApplication = ConfigFactory.parseString(config.getString("vcap_application"))

  val vcapServices = ConfigFactory.parseString(config.getString("vcap_services"))

  val dbServiceName = config.getString("db_service_name")

  def getCredentialsByName(vcapServices: String, name: String): DatabaseCreds = {
    import is.kow.scalatratrackerapp.config.VcapServicesFormat._
    val services = JsonParser(vcapServices).convertTo[Map[String, List[VcapService]]]

    val dbOption = services.flatMap { case (key, value) =>
      value
    }.find(service => {
      service.name == name
    })

    //Now I've got my database that I care about, lets start pulling out credentials
    //TODO: google cloudsql is it's own special nightmare
    //TODO: at some point check the tags to see what kind of database it is, and then I can do something with it, maybe

    val db = dbOption.getOrElse {
      throw new Exception(s"Unable to find database by name: $name")
    }

    val dbUser = db.credentials("username").convertTo[String]
    val dbPass = db.credentials("password").convertTo[String]
    val host = db.credentials("hostname").convertTo[String]
    val port = db.credentials("port").convertTo[Int]
    val dbName = db.credentials("name").convertTo[String]

    DatabaseCreds(host, port, dbUser, dbPass, dbName)
  }

  /**
    * Parse the vcap services and figure out if it's a google cloud sql, or a boring type
    *
    * @param vcapServices
    * @return
    */
  def parseCredentials(vcapServices: Config): DatabaseCreds = {
    if (vcapServices.atKey("google-cloudsql").isResolved) {
      //we have a google-cloudsql thing, so do all that wizardry
      log.info("Found Google-CloudSQL entry for services, doing the magic")

      val creds = vcapServices.getConfigList("google-cloudsql").get(0).getConfig("credentials")

      //Parse out the username, the password, the database name, host and stuff. Produce a JDBC URL
      val username = creds.getString("Username")
      val password = creds.getString("Password")
      val host = creds.getString("host")
      val port = 3307 //I guess google defaults to 3306?
      val instanceName = creds.getString("instance_name")
      val databaseName = creds.getString("database_name")

      //do some serious wizardry with the CA cert and client cert/key
      val caCert = creds.getString("CaCert")
      val clientCert = creds.getString("ClientCert")
      val clientKey = creds.getString("ClientKey")

      DatabaseCreds(host, port, username, password, databaseName,
        uri = Some(creds.getString("uri")),
        clientCert = Some(clientCert),
        caCert = Some(caCert),
        clientKey = Some(clientKey),
        instanceName = Some(instanceName)
      )
    } else if (vcapServices.atKey("p-mysql").isResolved) {
      log.info("More traditional p-mysql service entry found!")
      //This is pretty naive for the p-mysql value, doesn't work at all in google-cloudsql
      val dbRoot = vcapServices.getConfig("p-mysql").getConfigList("p-mysql").get(0).getConfig("credentials")
      val dbUser = dbRoot.getString("username")
      val dbPass = dbRoot.getString("password")
      val host = dbRoot.getString("hostname")
      val port = dbRoot.getInt("port")
      val databaseName = dbRoot.getString("name");

      DatabaseCreds(host, port, dbUser, dbPass, databaseName, jdbcUrl = Some(dbRoot.getString("jdbcUrl")))
    } else {
      throw new Exception("CRAP, couldn't identify the database service!")
    }
  }

  val dbCreds: DatabaseCreds = getCredentialsByName(config.getString("vcap_services"), dbServiceName)
}
