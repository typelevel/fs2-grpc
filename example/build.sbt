lazy val protobuf =
  project
    .in(file("."))
    .settings(
      PB.targets in Compile := List(
        scalapb.gen() -> (sourceManaged in Compile).value,
        fs2CodeGenerator -> (sourceManaged in Compile).value
      ),
      addCompilerPlugin(
        "org.spire-math" % "kind-projector" % "0.9.6" cross CrossVersion.binary)
    )

lazy val client =
  project
    .in(file("client"))
    .settings(
      libraryDependencies ++= List(
        "io.grpc" % "grpc-netty" % "1.11.0",
        "io.opencensus" % "opencensus-api" % "0.12.2",
        "io.opencensus" % "opencensus-impl" % "0.12.2",
        "io.opencensus" % "opencensus-contrib-zpages" % "0.12.2"
      )
    )
    .dependsOn(protobuf)

lazy val server =
  project
    .in(file("server"))
    .settings(
      libraryDependencies ++= List(
        "io.grpc" % "grpc-netty" % "1.11.0",
        "io.grpc" % "grpc-services" % "1.11.0",
        "io.opencensus" % "opencensus-api" % "0.12.2",
        "io.opencensus" % "opencensus-impl" % "0.12.2",
        "io.opencensus" % "opencensus-contrib-zpages" % "0.12.2",
        "io.opencensus" % "opencensus-exporter-trace-stackdriver" % "0.12.2"
      ),
      scalacOptions += "-Ypartial-unification",
      addCompilerPlugin(
        "org.spire-math" % "kind-projector" % "0.9.6" cross CrossVersion.binary)
    )
    .dependsOn(protobuf, client)
