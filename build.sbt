enablePlugins(JavaAppPackaging)

organization := "is.kow.trackerapp"
name := "Tracker App"
version := "2.0.0-SNAPSHOT"

scalaVersion := "2.11.8"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

//TODO: make sure this is useful This way running locally will always do debug logging
initialize ~= { _ =>
  System.setProperty("MY_LOG", "debug")
}

//Gonna use jitpack for my fixed dependency
resolvers += "jitpack" at "https://jitpack.io"
resolvers += Classpaths.typesafeReleases

libraryDependencies ++= {
  val akkaV = "2.4.10"
  val akkaHttpV = "10.0.1"
  val scalatraV = "2.4.1"
  val scalaTestV  = "3.0.1"

  Seq(
    "org.scalatest" %% "scalatest" % scalaTestV % "test",

    "org.flywaydb" % "flyway-core" % "4.0.3",
    "com.zaxxer" % "HikariCP" % "2.4.7",
    "com.typesafe.slick" %% "slick" % "3.1.0",
    "org.mariadb.jdbc" % "mariadb-java-client" % "1.4.6",
    "com.github.nscala-time" %% "nscala-time" % "2.12.0",

    "com.typesafe.play" %% "play-json" % "2.5.4",
    "com.github.tototoshi" %% "play-json-naming" % "1.1.0", //TODO: maybe akka http has a preferred JSON parser
    "com.ullink.slack" % "simpleslackapi" % "0.6.0",

    "org.apache.httpcomponents" % "httpasyncclient" % "4.1.2", //TODO: maybe use akka http client

    "com.typesafe.akka" %% "akka-actor" % akkaV,
    "com.typesafe.akka" %% "akka-slf4j" % akkaV,

    "ch.qos.logback" % "logback-classic" % "1.1.5" % "runtime" //TODO: change this to log4j2
  )
}

Revolver.settings