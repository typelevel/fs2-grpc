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
import fs2.grpc.codegen.Fs2AbstractServicePrinter.constants.{
  Async,
  Channel,
  ClientOptions,
  Companion,
  Ctx,
  Dispatcher,
  Fs2ClientCall,
  Fs2ServerCallHandler,
  Metadata,
  ServerOptions,
  ServerServiceDefinition,
  Stream
}
import scalapb.compiler.{DescriptorImplicits, FunctionalPrinter}
import scalapb.compiler.FunctionalPrinter.PrinterEndo

abstract class Fs2AbstractServicePrinter extends Fs2ServicePrinter {

  val service: ServiceDescriptor
  val serviceSuffix: String
  val di: DescriptorImplicits

  import di._

  private[this] val serviceName: String = service.name
  private[this] val serviceNameFs2: String = s"$serviceName${serviceSuffix}"
  private[this] val servicePkgName: String = service.getFile.scalaPackage.fullName

  protected def serviceMethodSignature(method: MethodDescriptor): String

  protected[this] def handleMethod(method: MethodDescriptor): String

  private[this] def createClientCall(method: MethodDescriptor) = {
    val basicClientCall =
      s"$Fs2ClientCall[F](channel, ${method.grpcDescriptor.fullName}, dispatcher, clientOptions)"
    if (method.isServerStreaming)
      s"$Stream.eval($basicClientCall)"
    else
      basicClientCall
  }

  private[this] def serviceMethodImplementation(method: MethodDescriptor): PrinterEndo = { p =>
    val mkMetadata = if (method.isServerStreaming) s"$Stream.eval(mkMetadata(ctx))" else "mkMetadata(ctx)"

    p.add(serviceMethodSignature(method) + " = {")
      .indent
      .add(s"$mkMetadata.flatMap { m =>")
      .indent
      .add(s"${createClientCall(method)}.flatMap(_.${handleMethod(method)}(request, m))")
      .outdent
      .add("}")
      .outdent
      .add("}")
  }

  private[this] def serviceBindingImplementation(method: MethodDescriptor): PrinterEndo = { p =>
    val inType = method.inputType.scalaType
    val outType = method.outputType.scalaType
    val descriptor = method.grpcDescriptor.fullName
    val handler = s"$Fs2ServerCallHandler[F](dispatcher, serverOptions).${handleMethod(method)}[$inType, $outType]"

    val serviceCall = s"serviceImpl.${method.name}"
    val eval = if (method.isServerStreaming) s"$Stream.eval(mkCtx(m))" else "mkCtx(m)"

    p.add(s".addMethod($descriptor, $handler((r, m) => $eval.flatMap($serviceCall(r, _))))")
  }

  private[this] def serviceMethods: PrinterEndo = _.seq(service.methods.map(serviceMethodSignature))

  private[this] def serviceMethodImplementations: PrinterEndo =
    _.call(service.methods.map(serviceMethodImplementation): _*)

  private[this] def serviceBindingImplementations: PrinterEndo =
    _.indent
      .add(s".builder(${service.grpcDescriptor.fullName})")
      .call(service.methods.map(serviceBindingImplementation): _*)
      .add(".build()")
      .outdent

  private[this] def serviceTrait: PrinterEndo =
    _.add(s"trait $serviceNameFs2[F[_], $Ctx] {").indent.call(serviceMethods).outdent.add("}")

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
    _.add(
      s"def mkClient[F[_]: $Async, $Ctx](dispatcher: $Dispatcher[F], channel: $Channel, mkMetadata: $Ctx => F[$Metadata], clientOptions: $ClientOptions): $serviceNameFs2[F, $Ctx] = new $serviceNameFs2[F, $Ctx] {"
    ).indent
      .call(serviceMethodImplementations)
      .outdent
      .add("}")
  }

  private[this] def serviceBinding: PrinterEndo = {
    _.add(
      s"protected def serviceBinding[F[_]: $Async, $Ctx](dispatcher: $Dispatcher[F], serviceImpl: $serviceNameFs2[F, $Ctx], mkCtx: $Metadata => F[$Ctx], serverOptions: $ServerOptions): $ServerServiceDefinition = {"
    ).indent
      .add(s"$ServerServiceDefinition")
      .call(serviceBindingImplementations)
      .outdent
      .add("}")
  }

  // /

  def printService(printer: FunctionalPrinter): FunctionalPrinter = {
    printer
      .add(s"package $servicePkgName", "", "import _root_.cats.syntax.all._", "")
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

  }

}
