lazy val root = project
  .in(file("."))
  .enablePlugins(Fs2Grpc)
  .settings(
    scalaVersion := "3.7.3",
    scalapbCodeGeneratorOptions += CodeGeneratorOption.Scala3Sources,
    scalapbCodeGeneratorOptions += CodeGeneratorOption.Fs2GrpcRenderContextAsImplicit
  )
