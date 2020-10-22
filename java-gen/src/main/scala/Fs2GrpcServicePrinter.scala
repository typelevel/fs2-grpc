package org.lyranthe.fs2_grpc.java_runtime.sbt_gen

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
      s"$Fs2ClientCall[F](channel, ${method.grpcDescriptor.fullName}, $CallOptionsFnName($CallOptions.DEFAULT), dispatcher, $ErrorAdapterName)"
    if (method.isServerStreaming)
      s"$Stream.eval($basicClientCall)"
    else
      basicClientCall
  }

  private[this] def serviceMethodImplementation(method: MethodDescriptor): PrinterEndo = { p =>
    p.add(serviceMethodSignature(method) + " = {")
      .indent
      .add(
        s"${createClientCall(method)}.flatMap(_.${handleMethod(method)}(request, f(ctx)))"
      )
      .outdent
      .add("}")
  }

  private[this] def serviceBindingImplementation(method: MethodDescriptor): PrinterEndo = { p =>
    val inType = method.inputType.scalaType
    val outType = method.outputType.scalaType
    val descriptor = method.grpcDescriptor.fullName
    val handler = s"$Fs2ServerCallHandler[F](dispatcher).${handleMethod(method)}[$inType, $outType]"

    val serviceCall = s"serviceImpl.${method.name}"
    val eval = if (method.isServerStreaming) s"$Stream.eval(g(m))" else "g(m)"

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
      .call(serviceClient)
      .newline
      .call(serviceBinding)
      .outdent
      .newline
      .add("}")

  private[this] def serviceClient: PrinterEndo = {
    _.add(
      s"def client[F[_]: $Async, $Ctx](dispatcher: $Dispatcher[F], channel: $Channel, f: $Ctx => $Metadata, $CallOptionsFnDefault, $ErrorAdapterDefault): $serviceNameFs2[F, $Ctx] = new $serviceNameFs2[F, $Ctx] {"
    ).indent
      .call(serviceMethodImplementations)
      .outdent
      .add("}")
  }

  private[this] def serviceBinding: PrinterEndo = {
    _.add(
      s"def service[F[_]: $Async, $Ctx](dispatcher: $Dispatcher[F], serviceImpl: $serviceNameFs2[F, $Ctx], f: $Metadata => Either[$Error, $Ctx]): $ServerServiceDefinition = {"
    ).indent.newline
      .add(
        s"val g: $Metadata => F[$Ctx] = f(_).leftMap[Throwable]($FailedPrecondition.withDescription(_).asRuntimeException()).liftTo[F]"
      )
      .newline
      .add(s"$ServerServiceDefinition")
      .call(serviceBindingImplementations)
      .outdent
      .add("}")
  }

  ///

  def printService(printer: FunctionalPrinter): FunctionalPrinter = {
    printer
      .add(s"package $servicePkgName", "", "import _root_.cats.syntax.all._", "")
      .call(serviceTrait)
      .newline
      .call(serviceObject)
  }
}

object Fs2GrpcServicePrinter {

  object constants {

    private val effPkg = "_root_.cats.effect"
    private val grpcPkg = "_root_.io.grpc"
    private val jrtPkg = "_root_.org.lyranthe.fs2_grpc.java_runtime"
    private val fs2Pkg = "_root_.fs2"

    ///

    val Error = "String"
    val Ctx = "A"

    val Async = s"$effPkg.Async"
    val Resource = s"$effPkg.Resource"
    val Dispatcher = s"$effPkg.std.Dispatcher"
    val Stream = s"$fs2Pkg.Stream"

    val Fs2ServerCallHandler = s"$jrtPkg.server.Fs2ServerCallHandler"
    val Fs2ClientCall = s"$jrtPkg.client.Fs2ClientCall"
    val Companion = s"$jrtPkg.GeneratedCompanion"

    val ErrorAdapter = s"$grpcPkg.StatusRuntimeException => Option[Exception]"
    val ErrorAdapterName = "errorAdapter"
    val ErrorAdapterDefault = s"$ErrorAdapterName: $ErrorAdapter = _ => None"

    val ServerServiceDefinition = s"$grpcPkg.ServerServiceDefinition"
    val CallOptions = s"$grpcPkg.CallOptions"
    val CallOptionsFnName = "coFn"
    val CallOptionsFnDefault = s"$CallOptionsFnName: $CallOptions => $CallOptions = identity"
    val Channel = s"$grpcPkg.Channel"
    val Metadata = s"$grpcPkg.Metadata"
    val FailedPrecondition = s"$grpcPkg.Status.FAILED_PRECONDITION"

  }

}
