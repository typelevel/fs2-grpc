import com.typesafe.tools.mima.core._
import com.typesafe.tools.mima.core.ProblemFilters._
import Dependencies._

lazy val Scala3 = "3.1.1"
lazy val Scala213 = "2.13.8"
lazy val Scala212 = "2.12.15"

Global / lintUnusedKeysOnLoad := false

def dev(ghUser: String, name: String, email: String): Developer =
  Developer(ghUser, name, email, url(s"https://github.com/$ghUser"))

inThisBuild(
  List(
    scalaVersion := Scala3,
    crossScalaVersions := List(Scala212, Scala213, Scala3),
    tlBaseVersion := "2.4",
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
      // package private API
      ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.grpc.client.StreamIngest.create"),
      // deleted private classes
      ProblemFilters.exclude[MissingClassProblem]("fs2.grpc.client.Fs2UnaryClientCallListener*"),
      ProblemFilters.exclude[MissingClassProblem]("fs2.grpc.server.Fs2UnaryServerCallListener*")
    )
  )
)

//

lazy val root = project
  .in(file("."))
  .enablePlugins(BuildInfoPlugin, NoPublishPlugin)
  .aggregate(runtime, codegen, plugin, e2e, protocGen.agg)
  .dependsOn(protocGen.agg)

lazy val codegen = project
  .settings(
    name := "fs2-grpc-codegen",
    scalaVersion := Scala212,
    crossScalaVersions := List(Scala212),
    libraryDependencies += scalaPbCompiler
  )

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
      "codeGeneratorName" -> (codegen / name).value,
      "codeGeneratorFullName" -> codegenFullName,
      "runtimeName" -> (runtime / name).value
    ),
    libraryDependencies += scalaPbCompiler,
    addSbtPlugin(sbtProtoc)
  )

lazy val runtime = project
  .settings(
    name := "fs2-grpc-runtime",
    libraryDependencies ++= List(fs2, catsEffect, grpcApi) ++ List(grpcNetty, ceTestkit, ceMunit).map(_ % Test),
    Test / parallelExecution := false
  )

lazy val protocGen = protocGenProject("protoc-gen-fs2-grpc", codegen)
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

lazy val e2e = project
  .in(file("e2e"))
  .dependsOn(runtime)
  .enablePlugins(LocalCodeGenPlugin, BuildInfoPlugin, NoPublishPlugin)
  .settings(
    codeGenClasspath := (codegen / Compile / fullClasspath).value,
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
