import Dependencies._

inThisBuild(
  List(
    scalaVersion := "2.13.3",
    organization := "org.lyranthe.fs2-grpc",
    git.useGitDescribe := true,
    scmInfo := Some(ScmInfo(url("https://github.com/fiadliel/fs2-grpc"), "git@github.com:fiadliel/fs2-grpc.git"))
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
  .aggregate(`java-gen`, `sbt-java-gen`, `java-runtime`)

lazy val `java-gen` = project
  .enablePlugins(GitVersioning)
  .settings(
    scalaVersion := "2.12.11",
    publishTo := sonatypePublishToBundle.value,
    libraryDependencies += scalaPbCompiler
  )

lazy val `sbt-java-gen` = project
  .enablePlugins(GitVersioning, BuildInfoPlugin)
  .settings(
    scalaVersion := "2.12.11",
    publishTo := sonatypePublishToBundle.value,
    sbtPlugin := true,
    crossSbtVersions := List(sbtVersion.value),
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
    scalaVersion := "2.13.3",
    crossScalaVersions := List(scalaVersion.value, "2.12.11"),
    publishTo := sonatypePublishToBundle.value,
    libraryDependencies ++= List(fs2, catsEffect, grpcApi) ++ List(grpcNetty, ceTestkit, ceMunit).map(_ % Test),
    mimaPreviousArtifacts := Set(organization.value %% name.value % "0.3.0"),
    Test / parallelExecution := false,
    testFrameworks += new TestFramework("munit.Framework"),
    addCompilerPlugin(kindProjector)
  )
