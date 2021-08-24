import sbt._

object Dependencies {

  object Versions {
    val bouncyCastle = "1.68"
    val mouse = "1.0.4"
    val sttp = "3.3.13"
    val newtype = "0.4.4"
    val tofu = "0.10.3"
    val circe = "0.14.1"
    val fs2 = "2.5.9"
  }

  val bcprov = "org.bouncycastle" % "bcprov-jdk15on" % Versions.bouncyCastle
  val bcpkix = "org.bouncycastle" % "bcpkix-jdk15on" % Versions.bouncyCastle
  val mouse = "org.typelevel" %% "mouse" % Versions.mouse
  val sttpCore = "com.softwaremill.sttp.client3" %% "core" % Versions.sttp
  val newtype = "io.estatico" %% "newtype" % Versions.newtype
  val tofuCore = "tf.tofu" %% "tofu-core" % Versions.tofu
  val tofuLogging = "tf.tofu" %% "tofu-logging" % Versions.tofu
  val circeGeneric = "io.circe" %% "circe-generic" % Versions.circe
  val circeParser = "io.circe" %% "circe-parser" % Versions.circe
  val fs2Core = "co.fs2" %% "fs2-core" % Versions.fs2
}
