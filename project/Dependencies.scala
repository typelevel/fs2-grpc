import sbt._

object Dependencies {

  object versions {

    val grpc = scalapb.compiler.Version.grpcJavaVersion
    val scalaPb = scalapb.compiler.Version.scalapbVersion

    val fs2 = "3.0.0-M7"
    val catsEffect = "3.0.0-M5"
    val ceMunit = "0.12.0"

    val kindProjector = "0.10.3"
    val sbtProtoc = "1.0.0-RC7-1"

  }

  // Compile

  val fs2 = "co.fs2" %% "fs2-core" % versions.fs2
  val catsEffect = "org.typelevel" %% "cats-effect" % versions.catsEffect
  val grpcApi = "io.grpc" % "grpc-api" % versions.grpc

  // Testing

  val ceTestkit = "org.typelevel" %% "cats-effect-testkit" % versions.catsEffect
  val ceMunit = "org.typelevel" %% "munit-cats-effect-3" % versions.ceMunit
  val grpcNetty = "io.grpc" % "grpc-netty-shaded" % versions.grpc

  // Compiler & SBT Plugins

  val sbtProtoc = "com.thesamet" % "sbt-protoc" % versions.sbtProtoc
  val scalaPbCompiler = "com.thesamet.scalapb" %% "compilerplugin" % versions.scalaPb
  val scalaPbRuntime = "com.thesamet.scalapb" %% "scalapb-runtime" % versions.scalaPb
  val scalaPbGrpcRuntime = "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % versions.scalaPb

  val kindProjector = "org.typelevel" %% "kind-projector" % versions.kindProjector cross CrossVersion.binary

}
