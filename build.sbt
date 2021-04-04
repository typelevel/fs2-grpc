import Dependencies._

lazy val Scala3 = "3.0.0-RC2"
lazy val Scala213 = "2.13.5"
lazy val Scala212 = "2.12.13"

Global / lintUnusedKeysOnLoad := false

enablePlugins(SonatypeCiReleasePlugin)

inThisBuild(
  List(
    scalaVersion := Scala3,
    crossScalaVersions := List(Scala3, Scala213, Scala212),
    baseVersion := "1.0.0",
    startYear := Some(2018),
    organization := "org.lyranthe.fs2-grpc",
    publishGithubUser := "fiadliel",
    publishFullName := "Gary Coady",
    git.useGitDescribe := true,
    sonatypeProfileName := "org.lyranthe",
    homepage := Some(url("https://www.lyranthe.org/")),
    scmInfo := Some(ScmInfo(url("https://github.com/fiadliel/fs2-grpc"), "git@github.com:fiadliel/fs2-grpc.git")),
    licenses := Seq("MIT" -> url("https://github.com/fiadliel/fs2-grpc/blob/master/LICENSE"))
  ) ++ List(
    githubWorkflowJavaVersions := Seq("adopt@1.8", "adopt@1.11"),
    githubWorkflowBuild := Seq(
      WorkflowStep.Sbt(
        name = Some("Run tests"),
        commands = List("scalafmtCheckAll", "test")
      )
    ),
    githubWorkflowTargetBranches := List("*", "series/*")
  ) ++ List(
    spiewakCiReleaseSnapshots := false,
    spiewakMainBranches := Seq("master", "series/0.x")
  )
)

lazy val root = project
  .in(file("."))
  .enablePlugins(BuildInfoPlugin, NoPublishPlugin)
  .aggregate(`java-gen`, `sbt-java-gen`, `java-runtime`, e2e, protocGen.agg)

lazy val `java-gen` = project
  .settings(
    scalaVersion := Scala212,
    crossScalaVersions := List(Scala212),
    libraryDependencies += scalaPbCompiler
  )

lazy val `sbt-java-gen` = project
  .enablePlugins(BuildInfoPlugin)
  .settings(
    scalaVersion := Scala212,
    crossScalaVersions := List(Scala212),
    sbtPlugin := true,
    buildInfoPackage := "org.lyranthe.fs2_grpc.buildinfo",
    buildInfoKeys := Seq[BuildInfoKey](
      name,
      version,
      scalaVersion,
      sbtVersion,
      organization,
      "grpcVersion" -> versions.grpc,
      "codeGeneratorName" -> (`java-gen` / name).value
    ),
    libraryDependencies += scalaPbCompiler,
    addSbtPlugin(sbtProtoc)
  )

lazy val `java-runtime` = project
  .settings(
    libraryDependencies ++= List(fs2, catsEffect, grpcApi) ++ List(grpcNetty, ceTestkit, ceMunit).map(_ % Test),
    mimaPreviousArtifacts := Set(organization.value %% name.value % "0.3.0"),
    Test / parallelExecution := false
  )

lazy val codegenFullName =
  "org.lyranthe.fs2_grpc.java_runtime.sbt_gen.Fs2CodeGenerator"

lazy val protocGen = protocGenProject("protoc-gen", `java-gen`)
  .settings(
    Compile / mainClass := Some(codegenFullName),
    scalaVersion := Scala212,
    githubWorkflowArtifactUpload := false
  )

lazy val e2e = project
  .in(file("e2e"))
  .dependsOn(`java-runtime`)
  .enablePlugins(LocalCodeGenPlugin, BuildInfoPlugin, NoPublishPlugin)
  .settings(
    codeGenClasspath := (`java-gen` / Compile / fullClasspath).value,
    libraryDependencies := Nil,
    libraryDependencies ++= List(scalaPbGrpcRuntime, scalaPbRuntime, scalaPbRuntime % "protobuf", ceMunit % Test),
    Compile / PB.targets := Seq(
      scalapb.gen() -> (Compile / sourceManaged).value / "scalapb",
      genModule(codegenFullName + "$") -> (Compile / sourceManaged).value / "fs2-grpc"
    ),
    buildInfoPackage := "io.fs2.grpc.buildinfo",
    buildInfoKeys := Seq[BuildInfoKey](
      "sourceManaged" -> (Compile / sourceManaged).value / "fs2-grpc"
    ),
    githubWorkflowArtifactUpload := false
  )
