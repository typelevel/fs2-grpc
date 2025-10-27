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

import com.google.protobuf.Descriptors.{MethodDescriptor, ServiceDescriptor}
import scalapb.compiler.{DescriptorImplicits, FunctionalPrinter}
import scalapb.compiler.FunctionalPrinter.PrinterEndo
import scalapb.compiler.ProtobufGenerator.asScalaDocBlock

abstract class Fs2AbstractServicePrinter extends Fs2ServicePrinter {
  import Fs2AbstractServicePrinter.constants._

  val service: ServiceDescriptor
  val serviceSuffix: String
  val di: DescriptorImplicits
  protected[this] val renderContextAsImplicit: Boolean = false
  protected[this] val scala3Sources: Boolean = false

  import di._

  private[this] val serviceName: String = service.name
  private[this] val serviceNameFs2: String = s"$serviceName${serviceSuffix}"
  private[this] val servicePkgName: String = service.getFile.scalaPackage.fullName

  protected def serviceMethodSignature(method: MethodDescriptor): String

  protected[this] def handleMethod(method: MethodDescriptor): String

  protected[this] def visitMethod(method: MethodDescriptor): String =
    s"visit" + handleMethod(method).capitalize

  private[this] def createClientCall(method: MethodDescriptor) = {
    val basicClientCall =
      s"$Fs2ClientCall[G](channel, ${method.grpcDescriptor.fullName}, dispatcher, clientOptions)"
    if (method.isServerStreaming)
      s"$Stream.eval($basicClientCall)"
    else
      basicClientCall
  }

  protected[this] def renderCtxParameter(): String = {
    if (renderContextAsImplicit) {
      if (scala3Sources) {
        s")(using ctx: $Ctx"
      } else {
        s")(implicit ctx: $Ctx"
      }
    } else {
      s", ctx: $Ctx"
    }
  }

  private[this] def serviceMethodImplementation(method: MethodDescriptor): PrinterEndo = { p =>
    val inType = method.inputType.scalaType
    val outType = method.outputType.scalaType
    val descriptor = method.grpcDescriptor.fullName

    p.add(serviceMethodSignature(method) + " =")
      .indented {
        _.addStringMargin(
          s"""|clientAspect.${visitMethod(method)}[$inType, $outType](
              |  ${ClientCallContext}(ctx, $descriptor),
              |  request,
              |  (req, m) => ${createClientCall(method)}.flatMap(_.${handleMethod(method)}(req, m))
              |)""".stripMargin
        )
      }
  }

  private[this] def serviceBindingImplementation(method: MethodDescriptor): PrinterEndo = { p =>
    val inType = method.inputType.scalaType
    val outType = method.outputType.scalaType
    val descriptor = method.grpcDescriptor.fullName
    val handler = s"$Fs2ServerCallHandler[G](dispatcher, serverOptions).${handleMethod(method)}[$inType, $outType]"

    val serviceCall = s"serviceImpl.${method.name}"
    val invoke = if (renderContextAsImplicit) {
      if (scala3Sources) {
        s"$serviceCall(r)(using m)"
      } else {
        s"$serviceCall(r)(m)"
      }
    } else {
      s"$serviceCall(r, m)"
    }

    p.addStringMargin {
      s"""|.addMethod(
          |  $descriptor,
          |  $handler{ (r, m) => 
          |    serviceAspect.${visitMethod(method)}[$inType, $outType](
          |      ${ServiceCallContext}(m, $descriptor),
          |      r,
          |      (r, m) => $invoke
          |    )
          |  }
          |)"""
    }
  }

  private[this] def serviceMethodImplementations: PrinterEndo =
    _.call(service.methods.map(serviceMethodImplementation): _*)

  private[this] def serviceBindingImplementations: PrinterEndo =
    _.indent
      .add(s".builder(${service.grpcDescriptor.fullName})")
      .call(service.methods.map(serviceBindingImplementation): _*)
      .add(".build()")
      .outdent

  private[this] def serviceTrait: PrinterEndo =
    _.call(generateScalaDoc(service))
      .add(s"trait $serviceNameFs2[F[_], $Ctx] {")
      .indent
      .print(service.methods) { case (p, method) =>
        p.call(generateScalaDoc(method)).add(serviceMethodSignature(method))
      }
      .outdent
      .add("}")

  private[this] def serviceObject: PrinterEndo =
    _.add(s"object $serviceNameFs2 extends $Companion[$serviceNameFs2] {").indent.newline
      .call(serviceDescriptor)
      .newline
      .call(serviceClient)
      .newline
      .call(serviceBinding)
      .outdent
      .newline
      .add("}")

  private[this] def serviceDescriptor: PrinterEndo = {
    _.add(
      s"def serviceDescriptor: ${Fs2AbstractServicePrinter.constants.ServiceDescriptor} = ${service.grpcDescriptor.fullName}"
    )
  }

  private[this] def serviceClient: PrinterEndo = {
    _.addStringMargin(
      s"""|def mkClientFull[F[_], G[_]: $Async, $Ctx](
          |  dispatcher: $Dispatcher[G],
          |  channel: $Channel,
          |  clientAspect: ${ClientAspect}[F, G, $Ctx],
          |  clientOptions: $ClientOptions
          |): $serviceNameFs2[F, $Ctx] = new $serviceNameFs2[F, $Ctx] {"""
    ).indent
      .call(serviceMethodImplementations)
      .outdent
      .add("}")
  }

  private[this] def serviceBinding: PrinterEndo = {
    _.addStringMargin(
      s"""|protected def serviceBindingFull[F[_], G[_]: $Async, $Ctx](
          |  dispatcher: $Dispatcher[G],
          |  serviceImpl: $serviceNameFs2[F, $Ctx],
          |  serviceAspect: ${ServiceAspect}[F, G, $Ctx],
          |  serverOptions: $ServerOptions
          |) = {"""
    ).indent
      .add(s"$ServerServiceDefinition")
      .call(serviceBindingImplementations)
      .outdent
      .add("}")
  }

  private[this] def generateScalaDoc(service: ServiceDescriptor): PrinterEndo = { fp =>
    val lines = asScalaDocBlock(service.comment.map(_.split('\n').toSeq).getOrElse(Seq.empty))
    fp.add(lines: _*)
  }

  private[this] def generateScalaDoc(method: MethodDescriptor): PrinterEndo = { fp =>
    val lines = asScalaDocBlock(method.comment.map(_.split('\n').toSeq).getOrElse(Seq.empty))
    fp.add(lines: _*)
  }

  def printService(printer: FunctionalPrinter): FunctionalPrinter = {
    printer
      .add(s"package $servicePkgName", "", s"import _root_.cats.syntax.all.${service.getFile.V.WildcardImport}", "")
      .call(serviceTrait)
      .newline
      .call(serviceObject)
  }
}

object Fs2AbstractServicePrinter {
  private[codegen] object constants {

    private val effPkg = "_root_.cats.effect"
    private val fs2Pkg = "_root_.fs2"
    private val fs2grpcPkg = "_root_.fs2.grpc"
    private val grpcPkg = "_root_.io.grpc"
    private val fs2grpcServerPkg = "_root_.fs2.grpc.server"
    private val fs2grpcClientPkg = "_root_.fs2.grpc.client"

    // /

    val Ctx = "A"

    val Async = s"$effPkg.Async"
    val Resource = s"$effPkg.Resource"
    val Dispatcher = s"$effPkg.std.Dispatcher"
    val Stream = s"$fs2Pkg.Stream"

    val Fs2ServerCallHandler = s"$fs2grpcPkg.server.Fs2ServerCallHandler"
    val Fs2ClientCall = s"$fs2grpcPkg.client.Fs2ClientCall"
    val ClientOptions = s"$fs2grpcPkg.client.ClientOptions"
    val ServerOptions = s"$fs2grpcPkg.server.ServerOptions"
    val Companion = s"$fs2grpcPkg.GeneratedCompanion"

    val ServerServiceDefinition = s"$grpcPkg.ServerServiceDefinition"
    val Channel = s"$grpcPkg.Channel"
    val Metadata = s"$grpcPkg.Metadata"
    val ServiceDescriptor = s"$grpcPkg.ServiceDescriptor"

    val ServiceAspect = s"${fs2grpcServerPkg}.ServiceAspect"
    val ServiceCallContext = s"${fs2grpcServerPkg}.ServiceCallContext"
    val ClientAspect = s"${fs2grpcClientPkg}.ClientAspect"
    val ClientCallContext = s"${fs2grpcClientPkg}.ClientCallContext"
  }

}
