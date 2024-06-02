import Dependencies.*

lazy val root = (project in file("."))
  .settings(
    organization := "su.wps",
    name := "trading-pillar",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "2.13.14",
    libraryDependencies ++= Seq(
      bcprov,
      bcpkix,
      mouse,
      sttpCore,
      newtype,
      enumeratum,
      tofuLogging,
      circeGeneric,
      circeParser,
      fs2Core,
      scalapbRuntime % "protobuf",
      grpcNetty
    ),
    addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.13.3" cross CrossVersion.patch),
    addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
  )
  .enablePlugins(Fs2Grpc)

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding",
  "UTF-8",
  "-language:implicitConversions",
  "-language:higherKinds",
  "-language:postfixOps",
  "-feature",
  "-Xfatal-warnings",
  "-Ymacro-annotations",
  "-Xsource:3"
)
