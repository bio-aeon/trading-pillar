import Dependencies.*

lazy val root = (project in file("."))
  .settings(
    organization := "st.btu",
    name := "trading-pillar",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "3.9.0",
    // generated Tinkoff protobuf/gRPC stubs are not our code; keep them out of coverage figures
    coverageExcludedPackages := "ru\\.tinkoff\\..*",
    libraryDependencies ++= Seq(
      bcprov,
      bcpkix,
      mouse,
      sttpCore,
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
    )
  )
  .enablePlugins(Fs2Grpc)

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding",
  "UTF-8",
  "-feature",
  "-Werror",
  // enumeratum's value-enum macro reads the entries' constructor arguments from retained trees
  "-Yretain-trees",
  // the generated Tinkoff stubs are not our code and trigger deprecation warnings on Scala 3
  "-Wconf:src=src_managed/.*:s"
)
