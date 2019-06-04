package org.lyranthe.fs2_grpc.java_gen

import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.compiler.PluginProtos
import com.google.protobuf.compiler.PluginProtos.{CodeGeneratorRequest, CodeGeneratorResponse}
import protocbridge.{Artifact, ProtocCodeGenerator}
import scala.collection.JavaConverters._
import scalapb.compiler.{FunctionalPrinter, GeneratorException, DescriptorImplicits, ProtobufGenerator}
import scalapb.options.compiler.Scalapb

class Fs2CodeGenerator(serviceSuffix: String) extends ProtocCodeGenerator {

  def generateServiceFiles(file: FileDescriptor,
                           di: DescriptorImplicits): Seq[PluginProtos.CodeGeneratorResponse.File] = {
    file.getServices.asScala.map { service =>
      val p = new Fs2GrpcServicePrinter(service, serviceSuffix, di)

      import di.{ServiceDescriptorPimp, FileDescriptorPimp}
      val code = p.printService(FunctionalPrinter()).result()
      val b    = CodeGeneratorResponse.File.newBuilder()
      b.setName(file.scalaDirectory + "/" + service.name + s"$serviceSuffix.scala")
      b.setContent(code)
      println(b.getName)
      b.build
    }
  }

  def handleCodeGeneratorRequest(request: PluginProtos.CodeGeneratorRequest): PluginProtos.CodeGeneratorResponse = {
    val b = CodeGeneratorResponse.newBuilder
    ProtobufGenerator.parseParameters(request.getParameter) match {
      case Right(params) =>
        try {

          val filesByName: Map[String, FileDescriptor] =
            request.getProtoFileList.asScala.foldLeft[Map[String, FileDescriptor]](Map.empty) {
              case (acc, fp) =>
                val deps = fp.getDependencyList.asScala.map(acc)
                acc + (fp.getName -> FileDescriptor.buildFrom(fp, deps.toArray))
            }

          val implicits = new DescriptorImplicits(params, filesByName.values.toVector)
          val genFiles  = request.getFileToGenerateList.asScala.map(filesByName)
          val srvFiles  = genFiles.flatMap(generateServiceFiles(_, implicits))
          b.addAllFile(srvFiles.asJava)
        } catch {
          case e: GeneratorException =>
            b.setError(e.message)
        }

      case Left(error) =>
        b.setError(error)
    }

    b.build()
  }

  override def run(req: Array[Byte]): Array[Byte] = {
    println("Running")
    val registry = ExtensionRegistry.newInstance()
    Scalapb.registerAllExtensions(registry)
    val request = CodeGeneratorRequest.parseFrom(req, registry)
    handleCodeGeneratorRequest(request).toByteArray
  }

  override def suggestedDependencies: Seq[Artifact] = Seq(
    Artifact("com.thesamet.scalapb", "scalapb-runtime", scalapb.compiler.Version.scalapbVersion, crossVersion = true)
  )
}
