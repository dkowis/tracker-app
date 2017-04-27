enablePlugins(JavaAppPackaging)

organization := "is.kow.trackerapp"
name := "Tracker App"
version := "2.3.0"

scalaVersion := "2.11.8"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

//TODO: make sure this is useful This way running locally will always do debug logging
//initialize ~= { _ =>
//  System.setProperty("MY_LOG", "debug")
//}

//Gonna use jitpack for my fixed dependency
resolvers += "jitpack" at "https://jitpack.io"
resolvers += Classpaths.typesafeReleases

libraryDependencies ++= {
  val akkaV = "2.5.0"
  val akkaHttpV = "10.0.5"
  val scalaTestV = "3.0.1"
  val log4jV = "2.8"
  val metricsV = "3.5.6_a2.3"

  Seq(
    //Akka http dependencies
    "com.typesafe.akka" %% "akka-actor" % akkaV,
    "com.typesafe.akka" %% "akka-stream" % akkaV,
    "com.typesafe.akka" %% "akka-testkit" % akkaV % "test",
    "com.typesafe.akka" %% "akka-http" % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpV % "test",
    "com.typesafe.akka" %% "akka-slf4j" % akkaV,

    //lets get some metrics up in my junk
    "nl.grons" %% "metrics-scala" % metricsV,

    //We like scalatest
    "org.scalatest" %% "scalatest" % scalaTestV % "test",

    //Database interaction dependencies
    "org.flywaydb" % "flyway-core" % "4.0.3",
    "com.zaxxer" % "HikariCP" % "2.6.0",
    "com.typesafe.slick" %% "slick" % "3.1.0",
    "org.mariadb.jdbc" % "mariadb-java-client" % "1.4.6",
    "com.github.nscala-time" %% "nscala-time" % "2.12.0",

    //http client related things. Maybe use akka-http client stuff to do this instead
    "com.typesafe.play" %% "play-json" % "2.5.4" exclude("ch.qos.logback", "logback-classic"),
    "com.github.tototoshi" %% "play-json-naming" % "1.1.0", //TODO: maybe akka http has a preferred JSON parser
    "com.mashape.unirest" % "unirest-java" % "1.4.9" exclude("commons-logging", "commons-logging"),

    //Slack API
    "com.ullink.slack" % "simpleslackapi" % "0.6.0" exclude("ch.qos.logback", "logback-classic"),

    //Logging
    "org.slf4j" % "jcl-over-slf4j" % "1.7.25",
    "org.apache.logging.log4j" % "log4j-api" % log4jV,
    "org.apache.logging.log4j" % "log4j-core" % log4jV,
    "org.apache.logging.log4j" % "log4j-slf4j-impl" % log4jV
  )
}

Revolver.settings