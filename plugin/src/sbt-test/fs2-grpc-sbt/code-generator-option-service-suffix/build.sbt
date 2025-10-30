lazy val root = project
  .in(file("."))
  .enablePlugins(Fs2Grpc)
  .settings(
    scalaVersion := "3.7.3",
    fs2GrpcServiceSuffix := "SomeSuffix",
    scalapbCodeGeneratorOptions += CodeGeneratorOption.Scala3Sources
  )
