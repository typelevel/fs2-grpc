package org.lyranthe.fs2_grpc.java_runtime.sbt_gen

import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.compiler.PluginProtos
import com.google.protobuf.compiler.PluginProtos.{CodeGeneratorRequest, CodeGeneratorResponse}
import protocbridge.ProtocCodeGenerator
import scalapb.compiler.{FunctionalPrinter, GeneratorException, DescriptorImplicits, GeneratorParams}
import scalapb.options.compiler.Scalapb
import scala.collection.JavaConverters._

case class Fs2Params(serviceSuffix: String = "Fs2Grpc")

object Fs2CodeGenerator extends ProtocCodeGenerator {

  def generateServiceFiles(
      file: FileDescriptor,
      fs2params: Fs2Params,
      di: DescriptorImplicits
  ): Seq[PluginProtos.CodeGeneratorResponse.File] = {
    file.getServices.asScala.map { service =>
      val p = new Fs2GrpcServicePrinter(service, fs2params.serviceSuffix, di)

      import di.{ServiceDescriptorPimp, FileDescriptorPimp}
      val code = p.printService(FunctionalPrinter()).result()
      val b = CodeGeneratorResponse.File.newBuilder()
      b.setName(file.scalaDirectory + "/" + service.name + s"${fs2params.serviceSuffix}.scala")
      b.setContent(code)
      println(b.getName)
      b.build
    }
  }

  private def parseParameters(params: String): Either[String, (GeneratorParams, Fs2Params)] =
    for {
      paramsAndUnparsed <- GeneratorParams.fromStringCollectUnrecognized(params)
      params = paramsAndUnparsed._1
      unparsed = paramsAndUnparsed._2
      suffix <- unparsed.map(_.split("=", 2).toList).foldLeft[Either[String, Fs2Params]](Right(Fs2Params())) {
        case (Right(params), ServiceSuffix :: suffix :: Nil) => Right(params.copy(serviceSuffix = suffix))
        case (Right(_), xs) => Left(s"Unrecognized parameter: $xs")
        case (Left(e), _) => Left(e)
      }
    } yield (params, suffix)

  def handleCodeGeneratorRequest(request: PluginProtos.CodeGeneratorRequest): PluginProtos.CodeGeneratorResponse = {
    val b = CodeGeneratorResponse.newBuilder
    parseParameters(request.getParameter()) match {
      case Right((params, fs2params)) =>
        try {
          val filesByName: Map[String, FileDescriptor] =
            request.getProtoFileList.asScala.foldLeft[Map[String, FileDescriptor]](Map.empty) { case (acc, fp) =>
              val deps = fp.getDependencyList.asScala.map(acc)
              acc + (fp.getName -> FileDescriptor.buildFrom(fp, deps.toArray))
            }

          val implicits = new DescriptorImplicits(params, filesByName.values.toVector)
          val genFiles = request.getFileToGenerateList.asScala.map(filesByName)
          val srvFiles = genFiles.flatMap(generateServiceFiles(_, fs2params, implicits))
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

  private[fs2_grpc] val ServiceSuffix: String = "serviceSuffix"
}
