import com.typesafe.tools.mima.core._
import com.typesafe.tools.mima.core.ProblemFilters._
import Dependencies._

lazy val Scala3 = "3.0.0-RC2"
lazy val Scala213 = "2.13.5"
lazy val Scala212 = "2.12.13"

Global / lintUnusedKeysOnLoad := false

enablePlugins(SonatypeCiReleasePlugin)

def dev(ghUser: String, name: String, email: String): Developer =
  Developer(ghUser, name, email, url(s"https://github.com/$ghUser"))

inThisBuild(
  List(
    scalaVersion := Scala213,
    crossScalaVersions := List(Scala3, Scala213, Scala212),
    baseVersion := "1.0",
    versionIntroduced := Map(
      // First version under org.typelevel
      "2.12" -> "1.1.2",
      "2.13" -> "1.1.2",
      "3.0.0-RC2" -> "1.1.2"
    ),
    startYear := Some(2018),
    licenses := Seq(("MIT", url("https://github.com/typelevel/fs2-grpc/blob/master/LICENSE"))),
    organization := "org.typelevel",
    organizationName := "Gary Coady / Fs2 Grpc Developers",
    publishGithubUser := "rossabaker",
    publishFullName := "Ross A. Baker",
    homepage := Some(url("https://github.com/typelevel/fs2-grpc")),
    scmInfo := Some(ScmInfo(url("https://github.com/typelevel/fs2-grpc"), "git@github.com:typelevel/fs2-grpc.git")),
    developers := List(
      dev("fiadliel", "Gary Coady", "gary@lyranthe.org"),
      dev("rossabaker", "Ross A. Baker", "ross@rossabaker.com"),
      dev("ahjohannessen", "Alex Henning Johannessen", "ahjohannessen@gmail.com")
    )
  ) ++ List(
    githubWorkflowJavaVersions := Seq("adopt@1.8", "adopt@1.11"),
    githubWorkflowBuild := Seq(
      WorkflowStep.Sbt(
        name = Some("Run tests"),
        commands = List("scalafmtCheckAll", "test", "mimaReportBinaryIssues")
      )
    ),
    githubWorkflowTargetBranches := List("*", "series/*")
  ) ++ List(
    spiewakCiReleaseSnapshots := false,
    spiewakMainBranches := Seq("master", "series/0.x")
  ) ++ List(
    mimaBinaryIssueFilters ++= {
      Seq(
        // Made internal
        exclude[DirectMissingMethodProblem]("fs2.grpc.client.Fs2UnaryClientCallListener.apply"),
        exclude[DirectMissingMethodProblem]("fs2.grpc.client.Fs2StreamClientCallListener.apply")
      )
    }
  )
)

lazy val root = project
  .in(file("."))
  .enablePlugins(BuildInfoPlugin, NoPublishPlugin)
  .aggregate(runtime, codegen, plugin, e2e)
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

lazy val protocGen = protocGenProject("protoc-gen", codegen)
  .settings(
    Compile / mainClass := Some(codegenFullName),
    scalaVersion := Scala212,
    githubWorkflowArtifactUpload := false,
    publish / skip := true,
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
