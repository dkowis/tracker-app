import com.mojolly.scalate.ScalatePlugin.ScalateKeys._
import org.scalatra.sbt.ScalatraPlugin

organization := "is.kow.scalatratrackerapp"
lazy val root = (project in file(".")).enablePlugins(JettyPlugin, JavaAppPackaging)

name := "Scalatra Tracker App"
version := "1.0.0-SNAPSHOT"
scalaVersion := "2.11.8"

val ScalatraVersion = "2.4.1"

ScalatraPlugin.scalatraSettings

//Gonna use jitpack for my fixed dependency
resolvers += "jitpack" at "https://jitpack.io"
resolvers += Classpaths.typesafeReleases

libraryDependencies ++= Seq(
  "org.scalatra" %% "scalatra" % ScalatraVersion,
  "org.scalatra" %% "scalatra-scalate" % ScalatraVersion,
  "org.scalatra" %% "scalatra-specs2" % ScalatraVersion % "test",
  "com.typesafe" % "config" % "1.3.0",
  "com.zaxxer" % "HikariCP" % "2.4.7",
  //"com.escalatesoft.subcut" %% "subcut" % "2.1", //Trying out subcut for DI and stuff
  "org.mariadb.jdbc" % "mariadb-java-client" % "1.4.6",
  "com.github.nscala-time" %% "nscala-time" % "2.12.0",
  "com.github.tototoshi" %% "play-json-naming" % "1.1.0",
  "com.github.dkowis" %% "slack-scala-client" % "0.1.5",
  "com.typesafe.play" %% "play-json" % "2.5.4",
  "com.typesafe.play" %% "play-ws" % "2.5.4",
  "org.flywaydb" % "flyway-core" % "4.0.3",
  "com.typesafe.slick" %% "slick" % "3.1.0",

  "ch.qos.logback" % "logback-classic" % "1.1.5" % "runtime", //TODO: change this to log4j2
  "org.eclipse.jetty" % "jetty-webapp" % "9.2.15.v20160210" % "compile;container",
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
