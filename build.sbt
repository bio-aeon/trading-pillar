import Dependencies.*

lazy val root = (project in file("."))
  .settings(
    organization := "st.btu",
    name := "trading-pillar",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "2.13.18",
    coverageScalacPluginVersion := "2.5.2",
    // generated Tinkoff protobuf/gRPC stubs are not our code; keep them out of coverage figures
    coverageExcludedPackages := "ru\\.tinkoff\\..*",
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
      grpcNetty,
      yfinance4s,
      specs2Core % Test,
      specs2Scalacheck % Test,
      catsEffectTestingSpecs2 % Test,
      sttpCatsEffect % Test
    ),
    addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.13.4" cross CrossVersion.patch),
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
