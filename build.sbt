import Dependencies._

lazy val Scala3 = "3.0.0-RC2"
lazy val Scala213 = "2.13.5"
lazy val Scala212 = "2.12.13"

inThisBuild(
  List(
    scalaVersion := Scala3,
    crossScalaVersions := List(Scala3, Scala213, Scala212),
    organization := "org.lyranthe.fs2-grpc",
    git.useGitDescribe := true,
    scmInfo := Some(ScmInfo(url("https://github.com/fiadliel/fs2-grpc"), "git@github.com:fiadliel/fs2-grpc.git"))
  ) ++ List(
    githubWorkflowJavaVersions := Seq("adopt@1.8", "adopt@1.11"),
    githubWorkflowBuild := Seq(
      WorkflowStep.Sbt(
        name = Some("Run tests"),
        commands = List("scalafmtCheckAll", "test")
      )
    ),
    githubWorkflowPublishTargetBranches := Nil
  )
)

lazy val root = project
  .in(file("."))
  .enablePlugins(GitVersioning, BuildInfoPlugin)
  .settings(
    sonatypeProfileName := "org.lyranthe",
    skip in publish := true,
    pomExtra in Global := {
      <url>https://github.com/fiadliel/fs2-grpc</url>
        <licenses>
          <license>
            <name>MIT</name>
              <url>https://github.com/fiadliel/fs2-grpc/blob/master/LICENSE</url>
          </license>
        </licenses>
        <developers>
          <developer>
            <id>fiadliel</id>
            <name>Gary Coady</name>
            <url>https://www.lyranthe.org/</url>
          </developer>
        </developers>
    }
  )
  .aggregate(`java-gen`, `sbt-java-gen`, `java-runtime`, e2e)

lazy val `java-gen` = project
  .enablePlugins(GitVersioning)
  .settings(
    scalaVersion := Scala212,
    crossScalaVersions := List(Scala212),
    publishTo := sonatypePublishToBundle.value,
    libraryDependencies += scalaPbCompiler
  )

lazy val `sbt-java-gen` = project
  .enablePlugins(GitVersioning, BuildInfoPlugin)
  .settings(
    scalaVersion := Scala212,
    crossScalaVersions := List(Scala212),
    publishTo := sonatypePublishToBundle.value,
    sbtPlugin := true,
    buildInfoPackage := "org.lyranthe.fs2_grpc.buildinfo",
    buildInfoKeys := Seq[BuildInfoKey](
      name,
      version,
      scalaVersion,
      sbtVersion,
      organization,
      "grpcVersion" -> versions.grpc,
      "codeGeneratorName" -> (name in `java-gen`).value
    ),
    libraryDependencies += scalaPbCompiler,
    addSbtPlugin(sbtProtoc)
  )

lazy val `java-runtime` = project
  .enablePlugins(GitVersioning)
  .settings(
    publishTo := sonatypePublishToBundle.value,
    libraryDependencies ++= List(fs2, catsEffect, grpcApi) ++ List(grpcNetty, ceTestkit, ceMunit).map(_ % Test) ++ {
      if(isDotty.value) Seq() else Seq(compilerPlugin(kindProjector))
    },
    mimaPreviousArtifacts := Set(organization.value %% name.value % "0.3.0"),
    Test / parallelExecution := false,
    testFrameworks += new TestFramework("munit.Framework")    
  )

lazy val codegenFullName =
  "org.lyranthe.fs2_grpc.java_runtime.sbt_gen.Fs2CodeGenerator"

lazy val protocGen = protocGenProject("protoc-gen", `java-gen`)
  .settings(
    Compile / mainClass := Some(codegenFullName),
    scalaVersion := Scala212
  )

lazy val e2e = project
  .in(file("e2e"))
  .dependsOn(`java-runtime`)
  .enablePlugins(LocalCodeGenPlugin, BuildInfoPlugin)
  .settings(
    skip in publish := true,
    codeGenClasspath := (`java-gen` / Compile / fullClasspath).value,
    libraryDependencies := Nil,
    libraryDependencies ++= List(scalaPbGrpcRuntime, scalaPbRuntime, scalaPbRuntime % "protobuf", ceMunit % Test),
    testFrameworks += new TestFramework("munit.Framework"),
    PB.targets in Compile := Seq(
      scalapb.gen() -> (sourceManaged in Compile).value / "scalapb",
      genModule(codegenFullName + "$") -> (sourceManaged in Compile).value / "fs2-grpc"
    ),
    buildInfoPackage := "io.fs2.grpc.buildinfo",
    buildInfoKeys := Seq[BuildInfoKey](
      "sourceManaged" -> (sourceManaged in Compile).value / "fs2-grpc"
    )
  )
