/*
 * Copyright (c) 2018 Gary Coady
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

package org.lyranthe.fs2_grpc.java_runtime.sbt_gen

import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.compiler.PluginProtos
import com.google.protobuf.compiler.PluginProtos.{CodeGeneratorRequest, CodeGeneratorResponse}
import protocbridge.ProtocCodeGenerator
import protocgen.CodeGenRequest
import scalapb.compiler.{DescriptorImplicits, FunctionalPrinter, GeneratorException, GeneratorParams}
import scalapb.options.Scalapb
import scala.collection.JavaConverters._

final case class Fs2Params(serviceSuffix: String = "Fs2Grpc")

object Fs2CodeGenerator extends ProtocCodeGenerator {

  def generateServiceFiles(
      file: FileDescriptor,
      fs2params: Fs2Params,
      di: DescriptorImplicits
  ): Seq[PluginProtos.CodeGeneratorResponse.File] = {
    file.getServices.asScala.map { service =>
      val p = new Fs2GrpcServicePrinter(service, fs2params.serviceSuffix, di)

      import di.{ExtendedServiceDescriptor, ExtendedFileDescriptor}
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
    val builder = CodeGeneratorResponse.newBuilder
    val genRequest = CodeGenRequest(request)
    parseParameters(genRequest.parameter) match {
      case Right((params, fs2params)) =>
        try {
          val implicits = DescriptorImplicits.fromCodeGenRequest(params, genRequest)
          val srvFiles = genRequest.filesToGenerate.flatMap(generateServiceFiles(_, fs2params, implicits))
          builder.addAllFile(srvFiles.asJava)
        } catch {
          case e: GeneratorException =>
            builder.setError(e.message)
        }

      case Left(error) =>
        builder.setError(error)
    }

    builder.build()
  }

  override def run(req: Array[Byte]): Array[Byte] = {
    println("Running")
    val registry = ExtensionRegistry.newInstance()
    Scalapb.registerAllExtensions(registry)
    val request = CodeGeneratorRequest.parseFrom(req, registry)
    handleCodeGeneratorRequest(request).toByteArray()
  }

  private[fs2_grpc] val ServiceSuffix: String = "serviceSuffix"
}
