import sbt._

object Dependencies {

  object versions {

    val grpc = scalapb.compiler.Version.grpcJavaVersion
    val scalaPb = scalapb.compiler.Version.scalapbVersion

    val fs2 = "3.12.0"
    val catsEffect = "3.6.2"
    val ceMunit = "2.1.0"

    val sbtProtoc = "1.0.8"

  }

  // Compile

  val fs2 = "co.fs2" %% "fs2-core" % versions.fs2
  val catsEffect = "org.typelevel" %% "cats-effect" % versions.catsEffect
  val grpcApi = "io.grpc" % "grpc-api" % versions.grpc

  // Testing

  val ceTestkit = "org.typelevel" %% "cats-effect-testkit" % versions.catsEffect
  val ceMunit = "org.typelevel" %% "munit-cats-effect" % versions.ceMunit
  val grpcNetty = "io.grpc" % "grpc-netty-shaded" % versions.grpc

  // Compiler & SBT Plugins

  val sbtProtoc = "com.thesamet" % "sbt-protoc" % versions.sbtProtoc
  val scalaPbCompiler = "com.thesamet.scalapb" %% "compilerplugin" % versions.scalaPb
  val scalaPbRuntime = "com.thesamet.scalapb" %% "scalapb-runtime" % versions.scalaPb
  val scalaPbGrpcRuntime = "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % versions.scalaPb

}
