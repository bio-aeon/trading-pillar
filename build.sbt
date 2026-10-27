import Dependencies.*

lazy val root = (project in file("."))
  .settings(
    organization := "st.btu",
    name := "trading-pillar",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "3.9.0",
    // code generated from the Tinkoff contracts, and from the Google annotations they import, is
    // not our code; keep it out of coverage figures
    coverageExcludedPackages := "ru\\.tinkoff\\..*;st\\.btu\\.trading\\.pillar\\.contracts\\..*",
    // TcsFacade.ApiVersion names the contracts dependency, so a bump of it changes the version too
    buildInfoKeys := Seq[BuildInfoKey](
      "investContracts" -> s"${investContracts.name} ${investContracts.revision}"
    ),
    buildInfoPackage := "st.btu.trading.pillar.facades",
    buildInfoObject := "ContractsInfo",
    buildInfoOptions += BuildInfoOption.PackagePrivate,
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
      mcpServer,
      mcpServerHttp4s,
      http4sEmberServer,
      scalapbRuntime % "protobuf",
      // only the contracts' .proto files are used, compiled here to Scala; the jar's Java classes
      // are not
      (investContracts % "protobuf-src").intransitive(),
      grpcNetty,
      yfinance4s,
      specs2Core % Test,
      specs2Scalacheck % Test,
      catsEffectTestingSpecs2 % Test,
      sttpCatsEffect % Test
    )
  )
  .enablePlugins(Fs2Grpc, BuildInfoPlugin)

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
