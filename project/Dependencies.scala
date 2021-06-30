import sbt._

object Dependencies {
  val scalaVer = "2.13.3"
  // #dependencies
  val ScalaTestVersion      = "3.1.1"
  val AkkaVersion           = "2.6.8"
  val circeVersion          = "0.12.3"
  val sttpVersion           = "2.2.7"
  val mapDBVersion          = "3.0.8"
  val typeSafeConfigVersion = "1.4.0"
  val jlineVersion          = "3.16.0"
  val nscalaTimeVersion     = "2.24.0"
  val scalaTestVersion      = "3.1.1"

  val dependencies = List(
    // Akka Actors
    "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
    // Application Config
    "com.typesafe" % "config" % typeSafeConfigVersion,
    //circe for parsing json
    "io.circe" %% "circe-core"    % circeVersion,
    "io.circe" %% "circe-generic" % circeVersion,
    "io.circe" %% "circe-parser"  % circeVersion,
    // Http Client via Sttp
    "com.softwaremill.sttp.client" %% "async-http-client-backend-future" % sttpVersion,
    "com.softwaremill.sttp.client" %% "circe"                            % sttpVersion,
    // MapDB for persistent HashMap
    "org.mapdb" % "mapdb" % mapDBVersion,
    //jline for cli progressbar
    "org.jline" % "jline" % jlineVersion,
    //nscala-time
    "com.github.nscala-time" %% "nscala-time" % nscalaTimeVersion,
    // Test Dependencies
    "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion      % Test,
    "org.scalatest"     %% "scalatest"                % scalaTestVersion % Test
  )
  // #dependencies
}
