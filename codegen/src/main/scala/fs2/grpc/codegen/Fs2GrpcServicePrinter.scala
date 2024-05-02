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
import scalapb.compiler.FunctionalPrinter.PrinterEndo
import scalapb.compiler.{DescriptorImplicits, FunctionalPrinter, StreamType}

class Fs2GrpcServicePrinter(service: ServiceDescriptor, serviceSuffix: String, di: DescriptorImplicits) {
  import di._
  import Fs2GrpcServicePrinter.constants._

  private[this] val serviceName: String = service.name
  private[this] val serviceNameFs2: String = s"$serviceName$serviceSuffix"
  private[this] val servicePkgName: String = service.getFile.scalaPackage.fullName

  private[this] def serviceMethodSignature(method: MethodDescriptor) = {

    val scalaInType = method.inputType.scalaType
    val scalaOutType = method.outputType.scalaType
    val ctx = s"ctx: $Ctx"

    s"def ${method.name}" + (method.streamType match {
      case StreamType.Unary => s"(request: $scalaInType, $ctx): F[$scalaOutType]"
      case StreamType.ClientStreaming => s"(request: $Stream[F, $scalaInType], $ctx): F[$scalaOutType]"
      case StreamType.ServerStreaming => s"(request: $scalaInType, $ctx): $Stream[F, $scalaOutType]"
      case StreamType.Bidirectional => s"(request: $Stream[F, $scalaInType], $ctx): $Stream[F, $scalaOutType]"
    })
  }

  private[this] def handleMethod(method: MethodDescriptor) = {
    method.streamType match {
      case StreamType.Unary => "unaryToUnaryCall"
      case StreamType.ClientStreaming => "streamingToUnaryCall"
      case StreamType.ServerStreaming => "unaryToStreamingCall"
      case StreamType.Bidirectional => "streamingToStreamingCall"
    }
  }

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

  private[this] def serviceBindingResourceImplementation(method: MethodDescriptor): PrinterEndo = { p =>
    val inType = method.inputType.scalaType
    val outType = method.outputType.scalaType
    val descriptor = method.grpcDescriptor.fullName
    val handler = s"$Fs2ServerCallHandler[F](dispatcher, serverOptions).${handleMethod(method)}[$inType, $outType]"

    val serviceCall = s"serviceImpl.${method.name}"
    val eval = if (method.isServerStreaming) s"$Stream.resource[F, A](mkCtx(m)).flatMap" else "mkCtx(m).use"

    p.add(s".addMethod($descriptor, $handler((r, m) => $eval($serviceCall(r, _))))")
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

  private[this] def serviceBindingResourceImplementations: PrinterEndo =
    _.indent
      .add(s".builder(${service.grpcDescriptor.fullName})")
      .call(service.methods.map(serviceBindingResourceImplementation): _*)
      .add(".build()")
      .outdent

  private[this] def serviceTrait: PrinterEndo =
    _.add(s"trait $serviceNameFs2[F[_], $Ctx] {").indent.call(serviceMethods).outdent.add("}")

  private[this] def serviceObject: PrinterEndo =
    _.add(s"object $serviceNameFs2 extends $Companion[$serviceNameFs2] {").indent.newline
      .call(serviceClient)
      .newline
      .call(serviceBinding)
      .newline
      .call(serviceBindingResource)
      .outdent
      .newline
      .add("}")

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

  private[this] def serviceBindingResource: PrinterEndo = {
    _.add(
      s"protected def serviceBindingResource[F[_]: $Async, $Ctx](dispatcher: $Dispatcher[F], serviceImpl: $serviceNameFs2[F, $Ctx], mkCtx: $Metadata => $Resource[F, $Ctx], serverOptions: $ServerOptions): $ServerServiceDefinition = {"
    ).indent
      .add(s"$ServerServiceDefinition")
      .call(serviceBindingResourceImplementations)
      .outdent
      .add("}")
  }

  // /

  def printService(printer: FunctionalPrinter): FunctionalPrinter = {
    printer
      .add(s"package $servicePkgName", "", s"import _root_.cats.syntax.all.${service.getFile.V.WildcardImport}", "")
      .call(serviceTrait)
      .newline
      .call(serviceObject)
  }
}

object Fs2GrpcServicePrinter {

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

  }

}
