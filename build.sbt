enablePlugins(JavaAppPackaging)

organization := "is.kow.trackerapp"
name := "Tracker App"
version := "2.5.0-SNAPSHOT"

scalaVersion := "2.12.2"

scalacOptions := Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8")

//TODO: make sure this is useful This way running locally will always do debug logging
//initialize ~= { _ =>
//  System.setProperty("MY_LOG", "debug")
//}

//Gonna use jitpack for my fixed dependency
resolvers += "jitpack" at "https://jitpack.io"
resolvers += Classpaths.typesafeReleases

libraryDependencies ++= {
  val akkaV = "2.5.1"
  val akkaHttpV = "10.0.6"
  val scalaTestV = "3.0.3"
  val log4jV = "2.8.2"
//  val metricsV = "3.5.6_a2.3"
  val metricsV = "3.5.6_a2.4"
  val slickV = "3.2.1"
  val scalaTimeV = "2.16.0"
  val guavaV = "22.0"

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
    "org.flywaydb" % "flyway-core" % "4.2.0",
    "com.zaxxer" % "HikariCP" % "2.6.1",
    "com.typesafe.slick" %% "slick" % slickV,
    "org.mariadb.jdbc" % "mariadb-java-client" % "2.1.2",
    "com.github.nscala-time" %% "nscala-time" % scalaTimeV,

    //http client related things. Maybe use akka-http client stuff to do this instead
    //Cannot use akka-http until https://github.com/akka/akka-http/pull/921 is merged, and released
    //Until then, this provides a simple asynchronous way of executing the HTTP calls
    "com.mashape.unirest" % "unirest-java" % "1.4.9" exclude("commons-logging", "commons-logging"),

    //Slack API
    //"com.ullink.slack" % "simpleslackapi" % "0.6.0" exclude("ch.qos.logback", "logback-classic"),
    "com.github.dkowis" % "simple-slack-api" % "uptodateness-SNAPSHOT",

    "com.google.guava" % "guava" % guavaV,

    //Logging
    "org.slf4j" % "jcl-over-slf4j" % "1.7.25",
    "org.apache.logging.log4j" % "log4j-api" % log4jV,
    "org.apache.logging.log4j" % "log4j-core" % log4jV,
    "org.apache.logging.log4j" % "log4j-slf4j-impl" % log4jV
  )
}

Revolver.settings
