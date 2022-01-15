/*
 * Copyright (c) 2018 Gary Coady / Fs2 Grpc Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2.grpc.codegen

import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.compiler.PluginProtos
import protocgen.{CodeGenApp, CodeGenRequest, CodeGenResponse}
import scalapb.compiler.{DescriptorImplicits, FunctionalPrinter, GeneratorParams}
import scalapb.options.Scalapb
import scala.collection.JavaConverters._

final case class Fs2Params(serviceSuffix: String = "Fs2Grpc")

object Fs2CodeGenerator extends CodeGenApp {

  def generateServiceFiles(
      file: FileDescriptor,
      fs2params: Fs2Params,
      di: DescriptorImplicits
  ): Seq[PluginProtos.CodeGeneratorResponse.File] = {
    file.getServices.asScala.map { service =>
      val p = new Fs2GrpcServicePrinter(service, fs2params.serviceSuffix, di)

      import di.{ExtendedServiceDescriptor, ExtendedFileDescriptor}
      val code = p.printService(FunctionalPrinter()).result()
      val b = PluginProtos.CodeGeneratorResponse.File.newBuilder()
      b.setName(file.scalaDirectory + "/" + service.name + s"${fs2params.serviceSuffix}.scala")
      b.setContent(code)
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

  def process(request: CodeGenRequest): CodeGenResponse = {
    parseParameters(request.parameter) match {
      case Right((params, fs2params)) =>
        val implicits = DescriptorImplicits.fromCodeGenRequest(params, request)
        val srvFiles = request.filesToGenerate.flatMap(generateServiceFiles(_, fs2params, implicits))
        CodeGenResponse.succeed(
          srvFiles,
          Set(PluginProtos.CodeGeneratorResponse.Feature.FEATURE_PROTO3_OPTIONAL)
        )
      case Left(error) =>
        CodeGenResponse.fail(error)
    }
  }

  override def registerExtensions(registry: ExtensionRegistry): Unit = {
    Scalapb.registerAllExtensions(registry)
  }

  @deprecated("Use process(CodeGenRequest(request)) instead. Method kept for binary compatibility.", "fs-grpc 2.2.6")
  def handleCodeGeneratorRequest(request: PluginProtos.CodeGeneratorRequest): PluginProtos.CodeGeneratorResponse = {
    process(CodeGenRequest(request)).toCodeGeneratorResponse
  }

  private[codegen] val ServiceSuffix: String = "serviceSuffix"
}
