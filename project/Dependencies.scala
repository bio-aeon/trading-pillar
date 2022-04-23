import sbt._

object Dependencies {

  object Versions {
    val bouncyCastle = "1.70"
    val mouse = "1.0.10"
    val sttp = "3.3.16"
    val newtype = "0.4.4"
    val enumeratum = "1.7.0"
    val tofu = "0.10.7"
    val circe = "0.14.1"
    val fs2 = "3.2.7"
    val scalapb = "0.11.10"
    val grpc = "1.45.1"
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
