import sbt.*

object Dependencies {

  object Versions {
    val bouncyCastle = "1.78.1"
    val mouse = "1.3.0"
    val sttp = "3.9.7"
    val newtype = "0.4.4"
    val enumeratum = "1.7.3"
    val tofu = "0.13.2"
    val circe = "0.14.7"
    val fs2 = "3.10.2"
    val scalapb = "0.11.15"
    val grpc = "1.64.0"
    val specs2 = "4.20.5"
    val catsEffectTesting = "1.5.0"
  }

  val bcprov = "org.bouncycastle" % "bcprov-jdk18on" % Versions.bouncyCastle
  val bcpkix = "org.bouncycastle" % "bcpkix-jdk18on" % Versions.bouncyCastle
  val mouse = "org.typelevel" %% "mouse" % Versions.mouse
  val sttpCore = "com.softwaremill.sttp.client3" %% "core" % Versions.sttp
  val sttpCatsEffect = "com.softwaremill.sttp.client3" %% "cats" % Versions.sttp
  val newtype = "io.estatico" %% "newtype" % Versions.newtype
  val enumeratum = "com.beachape" %% "enumeratum" % Versions.enumeratum
  val tofuLogging = "tf.tofu" %% "tofu-logging" % Versions.tofu
  val circeGeneric = "io.circe" %% "circe-generic" % Versions.circe
  val circeParser = "io.circe" %% "circe-parser" % Versions.circe
  val fs2Core = "co.fs2" %% "fs2-core" % Versions.fs2
  val scalapbRuntime = "com.thesamet.scalapb" %% "scalapb-runtime" % Versions.scalapb
  val grpcNetty = "io.grpc" % "grpc-netty-shaded" % Versions.grpc

  // Test dependencies
  val specs2Core = "org.specs2" %% "specs2-core" % Versions.specs2
  val specs2Scalacheck = "org.specs2" %% "specs2-scalacheck" % Versions.specs2
  val catsEffectTestingSpecs2 = "org.typelevel" %% "cats-effect-testing-specs2" % Versions.catsEffectTesting
}
