import Dependencies._

lazy val root = (project in file("."))
  .settings(
    organization := "su.wps",
    name := "trading-pillar",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "2.13.6",
    libraryDependencies ++= Seq(
      bcprov,
      bcpkix,
      mouse,
      sttpCore,
      newtype,
      enumeratum,
      tofuCore,
      tofuLogging,
      circeGeneric,
      circeParser,
      fs2Core
    ),
    addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.13.0" cross CrossVersion.patch),
    addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
  )

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding",
  "UTF-8",
  "-language:implicitConversions",
  "-language:higherKinds",
  "-language:postfixOps",
  "-feature",
  "-Xfatal-warnings",
  "-Ymacro-annotations"
)
