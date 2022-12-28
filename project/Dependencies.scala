import sbt._

object Dependencies {

  object Versions {
    val bouncyCastle = "1.70"
    val mouse = "1.2.1"
    val sttp = "3.8.5"
    val newtype = "0.4.4"
    val enumeratum = "1.7.2"
    val tofu = "0.11.1"
    val circe = "0.14.3"
    val fs2 = "3.4.0"
    val scalapb = "0.11.12"
    val grpc = "1.51.1"
  }

  val bcprov = "org.bouncycastle" % "bcprov-jdk15on" % Versions.bouncyCastle
  val bcpkix = "org.bouncycastle" % "bcpkix-jdk15on" % Versions.bouncyCastle
  val mouse = "org.typelevel" %% "mouse" % Versions.mouse
  val sttpCore = "com.softwaremill.sttp.client3" %% "core" % Versions.sttp
  val newtype = "io.estatico" %% "newtype" % Versions.newtype
  val enumeratum = "com.beachape" %% "enumeratum" % Versions.enumeratum
  val tofuLogging = "tf.tofu" %% "tofu-logging" % Versions.tofu
  val circeGeneric = "io.circe" %% "circe-generic" % Versions.circe
  val circeParser = "io.circe" %% "circe-parser" % Versions.circe
  val fs2Core = "co.fs2" %% "fs2-core" % Versions.fs2
  val scalapbRuntime = "com.thesamet.scalapb" %% "scalapb-runtime" % Versions.scalapb
  val grpcNetty = "io.grpc" % "grpc-netty-shaded" % Versions.grpc
}
