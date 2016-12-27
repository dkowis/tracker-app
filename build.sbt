import com.mojolly.scalate.ScalatePlugin.ScalateKeys._
import org.scalatra.sbt.ScalatraPlugin

organization := "is.kow.scalatratrackerapp"
lazy val root = (project in file(".")).enablePlugins(JettyPlugin, JavaAppPackaging)

name := "Scalatra Tracker App"
version := "1.3.1-SNAPSHOT"
scalaVersion := "2.11.8"

//This way running locally will always do debug logging
initialize ~= { _ =>
  System.setProperty("MY_LOG", "debug")
}


val ScalatraVersion = "2.4.1"
val akkaVersion = "2.4.10"

ScalatraPlugin.scalatraSettings

//Gonna use jitpack for my fixed dependency
resolvers += "jitpack" at "https://jitpack.io"
resolvers += Classpaths.typesafeReleases

libraryDependencies ++= Seq(
  "org.scalatra" %% "scalatra" % ScalatraVersion,
  "org.scalatra" %% "scalatra-scalate" % ScalatraVersion,
  "org.scalatra" %% "scalatra-specs2" % ScalatraVersion % "test",
  "org.scalatest" %% "scalatest" % "3.0.0" % "test",
  "com.zaxxer" % "HikariCP" % "2.4.7",
  "org.mariadb.jdbc" % "mariadb-java-client" % "1.4.6",
  "com.github.nscala-time" %% "nscala-time" % "2.12.0",
  "com.github.tototoshi" %% "play-json-naming" % "1.1.0",
  "com.ullink.slack" % "simpleslackapi" % "0.6.0",
  "com.typesafe.play" %% "play-json" % "2.5.4",
  "org.apache.httpcomponents" % "httpasyncclient" % "4.1.2",
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "org.flywaydb" % "flyway-core" % "4.0.3",
  "com.typesafe.slick" %% "slick" % "3.1.0",
  "ch.qos.logback" % "logback-classic" % "1.1.5" % "runtime", //TODO: change this to log4j2
  "org.eclipse.jetty" % "jetty-webapp" % "9.2.15.v20160210" % "container;compile",
  "javax.servlet" % "javax.servlet-api" % "3.1.0" % "provided"
)

scalateTemplateConfig in Compile <<= (sourceDirectory in Compile) { base =>
  Seq(
    TemplateConfig(
      base / "webapp" / "WEB-INF" / "templates",
      Seq.empty, /* default imports should be added here */
      Seq(
        Binding("context", "_root_.org.scalatra.scalate.ScalatraRenderContext", importMembers = true, isImplicit = true)
      ), /* add extra bindings here */
      Some("templates")
    )
  )
}

//addCompilerPlugin("com.escalatesoft.subcut" %% "subcut" % "2.1")
