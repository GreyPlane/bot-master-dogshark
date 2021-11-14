name := "DogShark-TheBotMaster"

version := "1.0"

scalaVersion := "2.13.1"

lazy val akkaVersion = "2.6.17"
lazy val akkaHttpVersion = "10.2.7"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation", "-explaintypes", "-feature", "-unchecked", "-Xlint",
   "-Ymacro-annotations")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "de.heikoseeberger" %% "akka-http-circe" % "1.38.2",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
  "org.scalatest" %% "scalatest" % "3.1.0" % Test
)


val circeVersion = "0.14.1"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion)