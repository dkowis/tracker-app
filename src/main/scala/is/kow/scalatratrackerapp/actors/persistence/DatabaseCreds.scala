package is.kow.scalatratrackerapp.actors.persistence

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
