import com.typesafe.tools.mima.core._
import com.typesafe.tools.mima.core.ProblemFilters._
import Dependencies._

lazy val Scala3 = "3.2.1"
lazy val Scala213 = "2.13.10"
lazy val Scala212 = "2.12.17"

lazy val axesDefault =
  Seq(VirtualAxis.scalaABIVersion(Scala213), VirtualAxis.jvm)

Global / lintUnusedKeysOnLoad := false

def dev(ghUser: String, name: String, email: String): Developer =
  Developer(ghUser, name, email, url(s"https://github.com/$ghUser"))

inThisBuild(
  List(
    scalaVersion := Scala3,
    crossScalaVersions := List(Scala212, Scala213, Scala3),
    tlBaseVersion := "2.5",
    startYear := Some(2018),
    licenses := Seq(("MIT", url("https://github.com/typelevel/fs2-grpc/blob/master/LICENSE"))),
    organizationName := "Gary Coady / Fs2 Grpc Developers",
    developers := List(
      dev("fiadliel", "Gary Coady", "gary@lyranthe.org"),
      dev("rossabaker", "Ross A. Baker", "ross@rossabaker.com"),
      dev("ahjohannessen", "Alex Henning Johannessen", "ahjohannessen@gmail.com")
    )
  ) ++ List(
    githubWorkflowJavaVersions := Seq(
      JavaSpec.temurin("8"),
      JavaSpec.temurin("17")
    )
  ) ++ List(
    mimaBinaryIssueFilters ++= Seq(
      // API that is not extended by end-users
      ProblemFilters.exclude[ReversedMissingMethodProblem]("fs2.grpc.GeneratedCompanion.mkClient"),
      // package private APIs
      ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.grpc.client.StreamIngest.create"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.grpc.server.Fs2StreamServerCallListener*"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.grpc.client.Fs2StreamClientCallListener*"),
      // deleted private classes
      ProblemFilters.exclude[MissingClassProblem]("fs2.grpc.client.Fs2UnaryClientCallListener*"),
      ProblemFilters.exclude[MissingClassProblem]("fs2.grpc.server.Fs2UnaryServerCallListener*")
    )
  )
)

lazy val projects =
  runtime.projectRefs ++ codegen.projectRefs ++ e2e.projectRefs ++ List(plugin.project, protocGen.agg.project)

lazy val root = (project in file("."))
  .enablePlugins(BuildInfoPlugin, NoPublishPlugin)
  .aggregate(projects: _*)
  .dependsOn(protocGen.agg)

lazy val codegen = (projectMatrix in file("codegen"))
  .defaultAxes(axesDefault: _*)
  .settings(
    name := "fs2-grpc-codegen",
    libraryDependencies += scalaPbCompiler,
    tlVersionIntroduced := Map(
      "2.12" -> "2.4.0",
      "2.13" -> "2.5.0-RC3",
      "3" -> "2.5.0-RC3"
    )
  )
  .jvmPlatform(scalaVersions = Seq(Scala212, Scala213, Scala3))

lazy val codegenFullName =
  "fs2.grpc.codegen.Fs2CodeGenerator"

lazy val plugin = project
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "sbt-fs2-grpc",
    scalaVersion := Scala212,
    crossScalaVersions := List(Scala212),
    sbtPlugin := true,
    buildInfoPackage := "fs2.grpc.buildinfo",
    buildInfoKeys := Seq[BuildInfoKey](
      name,
      version,
      scalaVersion,
      sbtVersion,
      organization,
      "grpcVersion" -> versions.grpc,
      "codeGeneratorName" -> (codegen.jvm(Scala212) / name).value,
      "codeGeneratorFullName" -> codegenFullName,
      "runtimeName" -> (runtime.jvm(Scala212) / name).value
    ),
    libraryDependencies += scalaPbCompiler,
    addSbtPlugin(sbtProtoc)
  )

lazy val runtime = (projectMatrix in file("runtime"))
  .defaultAxes(axesDefault: _*)
  .settings(
    name := "fs2-grpc-runtime",
    crossScalaVersions := List(Scala212, Scala213, Scala3),
    libraryDependencies ++= List(fs2, catsEffect, grpcApi) ++ List(grpcNetty, ceTestkit, ceMunit).map(_ % Test),
    Test / parallelExecution := false
  )
  .jvmPlatform(scalaVersions = Seq(Scala212, Scala213, Scala3))

lazy val codeGenJVM212 = codegen.jvm(Scala212)
lazy val protocGen = protocGenProject("protoc-gen-fs2-grpc", codeGenJVM212)
  .settings(
    Compile / mainClass := Some(codegenFullName),
    githubWorkflowArtifactUpload := false,
    scalaVersion := Scala212
  )
  .aggregateProjectSettings(
    githubWorkflowArtifactUpload := false,
    mimaFailOnNoPrevious := false,
    mimaPreviousArtifacts := Set()
  )

lazy val e2e = (projectMatrix in file("e2e"))
  .dependsOn(runtime)
  .defaultAxes(axesDefault: _*)
  .enablePlugins(LocalCodeGenPlugin, BuildInfoPlugin, NoPublishPlugin)
  .settings(
    crossScalaVersions := Seq(Scala212, Scala213, Scala3),
    codeGenClasspath := (codeGenJVM212 / Compile / fullClasspath).value,
    libraryDependencies := Nil,
    libraryDependencies ++= List(scalaPbGrpcRuntime, scalaPbRuntime, scalaPbRuntime % "protobuf", ceMunit % Test),
    Compile / PB.targets := Seq(
      scalapb.gen() -> (Compile / sourceManaged).value / "scalapb",
      genModule(codegenFullName + "$") -> (Compile / sourceManaged).value / "fs2-grpc"
    ),
    buildInfoPackage := "fs2.grpc.e2e.buildinfo",
    buildInfoKeys := Seq[BuildInfoKey]("sourceManaged" -> (Compile / sourceManaged).value / "fs2-grpc"),
    githubWorkflowArtifactUpload := false
  )
  .jvmPlatform(scalaVersions = Seq(Scala212, Scala213, Scala3))
