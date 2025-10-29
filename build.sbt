import com.typesafe.tools.mima.core._
import com.typesafe.tools.mima.core.ProblemFilters._
import Dependencies._

lazy val Scala3 = "3.3.7"
lazy val Scala213 = "2.13.17"
lazy val Scala212 = "2.12.20"

lazy val axesDefault =
  Seq(VirtualAxis.scalaABIVersion(Scala213), VirtualAxis.jvm)

Global / lintUnusedKeysOnLoad := false

def dev(ghUser: String, name: String, email: String): Developer =
  Developer(ghUser, name, email, url(s"https://github.com/$ghUser"))

inThisBuild(
  List(
    githubWorkflowBuildSbtStepPreamble := Seq(),
    scalaVersion := Scala3,
    tlBaseVersion := "3.0",
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
      ProblemFilters.exclude[ReversedMissingMethodProblem]("fs2.grpc.GeneratedCompanion.mkClientFull"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("fs2.grpc.GeneratedCompanion.serviceBindingFull"),
      ProblemFilters.exclude[ReversedMissingMethodProblem]("fs2.grpc.GeneratedCompanion.serviceDescriptor"),
      // package private APIs
      ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.grpc.client.StreamIngest.create"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.grpc.server.Fs2StreamServerCallListener*"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.grpc.client.Fs2StreamClientCallListener*"),
      ProblemFilters.exclude[MissingClassProblem]("fs2.grpc.client.StreamIngest*"),
      ProblemFilters.exclude[MissingClassProblem]("fs2.grpc.codegen.Fs2GrpcServicePrinter$constants$"),
      ProblemFilters.exclude[MissingFieldProblem]("fs2.grpc.codegen.Fs2GrpcServicePrinter.constants"),
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
      "2.12" -> "2.5.3",
      "2.13" -> "2.5.3",
      "3" -> "2.5.3"
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
    tlVersionIntroduced := Map("2.12" -> "2.5.3"),
    sbtPlugin := true,
    mimaFailOnNoPrevious := false,
    mimaPreviousArtifacts := Set(),
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
    tlVersionIntroduced := Map("2.12" -> "2.5.3", "2.13" -> "2.5.3", "3" -> "2.5.3"),
    libraryDependencies ++= List(fs2, catsEffect, grpcApi) ++ List(grpcNetty, ceTestkit, ceMunit).map(_ % Test),
    Test / parallelExecution := false,
    scalacOptions := {
      if (tlIsScala3.value) { scalacOptions.value.filterNot(_ == "-Ykind-projector:underscores") }
      else scalacOptions.value
    },
    scalacOptions ++= {
      if (tlIsScala3.value) { Seq("-language:implicitConversions", "-Ykind-projector", "-source:3.0-migration") }
      else Seq.empty
    }
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
    codeGenClasspath := (codeGenJVM212 / Compile / fullClasspath).value,
    libraryDependencies := Nil,
    libraryDependencies ++= List(
      scalaPbGrpcRuntime,
      scalaPbRuntime,
      scalaPbRuntime % "protobuf",
      ceMunit % Test,
      "io.grpc" % "grpc-inprocess" % versions.grpc % Test
    ),
    Compile / PB.targets := {
      val disableTrailers = new {
        val args = Seq("serviceSuffix=Fs2GrpcDisableTrailers", "disableTrailers=true")
        val output = (Compile / sourceManaged).value / "fs2-grpc" / "disable-trailers"
      }

      Seq(
        scalapb.gen() -> (Compile / sourceManaged).value / "scalapb",
        genModule(codegenFullName + "$") -> (Compile / sourceManaged).value / "fs2-grpc",
        (genModule(codegenFullName + "$"), disableTrailers.args) -> disableTrailers.output
      )
    },
    buildInfoPackage := "fs2.grpc.e2e.buildinfo",
    buildInfoKeys := Seq[BuildInfoKey]("sourceManaged" -> (Compile / sourceManaged).value / "fs2-grpc"),
    githubWorkflowArtifactUpload := false,
    scalacOptions := {
      if (tlIsScala3.value) {
        scalacOptions.value.filterNot(o => o == "-Ykind-projector:underscores" || o == "-Wvalue-discard")
      } else scalacOptions.value
    },
    scalacOptions ++= {
      if (tlIsScala3.value) { Seq("-language:implicitConversions", "-Ykind-projector", "-source:3.0-migration") }
      else Seq.empty
    }
  )
  .jvmPlatform(scalaVersions = Seq(Scala212, Scala213, Scala3))
