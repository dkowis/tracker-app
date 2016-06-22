name := """tracker-app"""
version := "1.0-SNAPSHOT"
lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  jdbc,
  cache,
  ws,
  "com.github.nscala-time" %% "nscala-time" % "2.12.0",
  "com.google.guava" % "guava" % "19",
  //Thank you this https://github.com/tototoshi/play-json-naming
  "com.github.tototoshi" %% "play-json-naming" % "1.1.0",
  "org.scalatestplus.play" %% "scalatestplus-play" % "1.5.1" % Test
)

// why is this in here? I don't want scalaz
//resolvers += "sonatype oss" at "https://oss.sonatype.org/content/repositories/releases/"
//resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"
