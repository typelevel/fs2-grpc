package org.lyranthe.fs2_grpc.java_runtime.sbt_gen

import org.lyranthe.fs2_grpc.java_gen.Fs2CodeGenerator
import org.lyranthe.fs2_grpc.java_runtime.sbt_gen.Fs2GrpcPlugin.autoImport.scalapbCodeGenerators
import protocbridge.{JvmGenerator, Target}
import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin
import sbtprotoc.ProtocPlugin.autoImport.PB

sealed trait CodeGeneratorOption extends Product with Serializable

object Fs2Grpc extends AutoPlugin {
  override def requires: Plugins      = Fs2GrpcPlugin
  override def trigger: PluginTrigger = NoTrigger

  override def projectSettings: Seq[Def.Setting[_]] = List(
    PB.targets := scalapbCodeGenerators.value
  )
}

object Fs2GrpcPlugin extends AutoPlugin {
  object autoImport {
    val grpcJavaVersion: String = scalapb.compiler.Version.grpcJavaVersion

    object CodeGeneratorOption {
      case object FlatPackage extends CodeGeneratorOption {
        override def toString = "flat_package"
      }
      case object JavaConversions extends CodeGeneratorOption {
        override def toString: String = "java_conversions"
      }
      case object Grpc extends CodeGeneratorOption {
        override def toString: String = "grpc"
      }
      case object Fs2Grpc extends CodeGeneratorOption {
        override def toString: String = "fs2_grpc"
      }
      case object SingleLineToProtoString extends CodeGeneratorOption {
        override def toString: String = "single_line_to_proto_string"
      }
      case object AsciiFormatToString extends CodeGeneratorOption {
        override def toString: String = "ascii_format_to_string"
      }
    }

    val scalapbCodeGeneratorOptions =
      settingKey[Seq[CodeGeneratorOption]]("Settings for scalapb/fs2-grpc code generation")
    val scalapbProtobufDirectory =
      settingKey[File]("Directory containing protobuf files for scalapb")
    val scalapbCodeGenerators =
      settingKey[Seq[Target]]("Code generators for scalapb")
    val fs2GrpcServiceSuffix =
      settingKey[String](
        "Suffix used for generated service, e.g. service `Foo` with suffix `Fs2Grpc` results in `FooFs2Grpc`"
      )

  }
  import autoImport._

  override def requires = sbtprotoc.ProtocPlugin && JvmPlugin
  override def trigger  = NoTrigger

  def convertOptionsToScalapbGen(options: Set[CodeGeneratorOption]): (JvmGenerator, Seq[String]) = {
    scalapb.gen(
      flatPackage = options(CodeGeneratorOption.FlatPackage),
      javaConversions = options(CodeGeneratorOption.JavaConversions),
      grpc = options(CodeGeneratorOption.Grpc),
      singleLineToProtoString = options(CodeGeneratorOption.SingleLineToProtoString),
      asciiFormatToString = options(CodeGeneratorOption.AsciiFormatToString)
    )
  }

  override def projectSettings: Seq[Def.Setting[_]] = List(
    fs2GrpcServiceSuffix := "Fs2Grpc",
    scalapbProtobufDirectory := (sourceManaged in Compile).value / "scalapb",
    scalapbCodeGenerators := {
      Target(convertOptionsToScalapbGen(scalapbCodeGeneratorOptions.value.toSet),
             (sourceManaged in Compile).value / "scalapb") ::
        Option(
        Target(
          (JvmGenerator("scala-fs2-grpc", new Fs2CodeGenerator(fs2GrpcServiceSuffix.value)),
           scalapbCodeGeneratorOptions.value.filterNot(_ == CodeGeneratorOption.Fs2Grpc).map(_.toString)),
          (sourceManaged in Compile).value / "fs2-grpc"
        ))
        .filter(_ => scalapbCodeGeneratorOptions.value.contains(CodeGeneratorOption.Fs2Grpc))
        .toList
    },
    scalapbCodeGeneratorOptions := Seq(CodeGeneratorOption.Grpc, CodeGeneratorOption.Fs2Grpc),
    libraryDependencies ++= List(
      "io.grpc"               % "grpc-core"             % scalapb.compiler.Version.grpcJavaVersion,
      "io.grpc"               % "grpc-stub"             % scalapb.compiler.Version.grpcJavaVersion,
      "org.lyranthe.fs2-grpc" %% "java-runtime"         % org.lyranthe.fs2_grpc.buildinfo.BuildInfo.version,
      "com.thesamet.scalapb"  %% "scalapb-runtime"      % scalapb.compiler.Version.scalapbVersion,
      "com.thesamet.scalapb"  %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion
    )
  )
}
