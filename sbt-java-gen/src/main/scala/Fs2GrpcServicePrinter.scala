package org.lyranthe.fs2_grpc.java_runtime.sbt_gen

import com.google.protobuf.Descriptors.{MethodDescriptor, ServiceDescriptor}
import scalapb.compiler.FunctionalPrinter.PrinterEndo
import scalapb.compiler.{DescriptorImplicits, FunctionalPrinter, StreamType}

class Fs2GrpcServicePrinter(service: ServiceDescriptor, serviceSuffix: String, di: DescriptorImplicits){
  import di._
  import Fs2GrpcServicePrinter.constants._

  private[this] val serviceName: String    = service.name
  private[this] val serviceNameFs2: String = s"$serviceName$serviceSuffix"
  private[this] val servicePkgName: String = service.getFile.scalaPackageName

  private[this] def serviceMethodSignature(method: MethodDescriptor) = {

    val scalaInType   = method.inputType.scalaType
    val scalaOutType  = method.outputType.scalaType
    val ctx           = s"ctx: $Ctx"

    s"def ${method.name}" + (method.streamType match {
      case StreamType.Unary           => s"(request: $scalaInType, $ctx): F[$scalaOutType]"
      case StreamType.ClientStreaming => s"(request: $Stream[F, $scalaInType], $ctx): F[$scalaOutType]"
      case StreamType.ServerStreaming => s"(request: $scalaInType, $ctx): $Stream[F, $scalaOutType]"
      case StreamType.Bidirectional   => s"(request: $Stream[F, $scalaInType], $ctx): $Stream[F, $scalaOutType]"
    })
  }

  private[this] def handleMethod(method: MethodDescriptor) = {
    method.streamType match {
      case StreamType.Unary           => "unaryToUnaryCall"
      case StreamType.ClientStreaming => "streamingToUnaryCall"
      case StreamType.ServerStreaming => "unaryToStreamingCall"
      case StreamType.Bidirectional   => "streamingToStreamingCall"
    }
  }

  private[this] def createClientCall(method: MethodDescriptor) = {
    val basicClientCall =
      s"$Fs2ClientCall[F](channel, _root_.$servicePkgName.${serviceName}Grpc.${method.descriptorName}, c($CallOptions.DEFAULT))"
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

    val inType      = method.inputType.scalaType
    val outType     = method.outputType.scalaType
    val descriptor  = s"_root_.$servicePkgName.${serviceName}Grpc.${method.descriptorName}"
    val handler     = s"$Fs2ServerCallHandler[F].${handleMethod(method)}[$inType, $outType]"

    val serviceCall = s"serviceImpl.${method.name}"
    val eval        = if(method.isServerStreaming) s"$Stream.eval(g(m))" else "g(m)"

    p.add(s".addMethod($descriptor, $handler((r, m) => $eval.flatMap($serviceCall(r, _))))")
  }

  private[this] def serviceMethods: PrinterEndo = _.seq(service.methods.map(serviceMethodSignature))

  private[this] def serviceMethodImplementations: PrinterEndo =
    _.call(service.methods.map(serviceMethodImplementation): _*)

  private[this] def serviceBindingImplementations: PrinterEndo =
    _.indent
      .add(s".builder(_root_.$servicePkgName.${serviceName}Grpc.${service.descriptorName})")
      .call(service.methods.map(serviceBindingImplementation): _*)
      .add(".build()")
      .outdent

  private[this] def serviceTrait: PrinterEndo =
    _.add(s"trait $serviceNameFs2[F[_], $Ctx] {").
      indent.
      call(serviceMethods).
      outdent.
      add("}")

  private[this] def serviceObject: PrinterEndo =
    _.add(s"object $serviceNameFs2 {").
      indent.
      newline.
      call(serviceClientMeta).
      newline.
      call(serviceClient).
      newline.
      call(serviceBindingMeta).
      newline.
      call(serviceBinding).
      outdent.
      add("}")

  private[this] def serviceClient: PrinterEndo = {
    _.add(
      s"def client[F[_]: $ConcurrentEffect, $Ctx](channel: $Channel, f: $Ctx => $Metadata, c: $CallOptions => $CallOptions = identity): $serviceNameFs2[F, $Ctx] = new $serviceNameFs2[F, $Ctx] {")
      .indent
      .call(serviceMethodImplementations)
      .outdent
      .add("}")
  }

  private[this] def serviceBinding: PrinterEndo = {
    _.add(
      s"def service[F[_]: $ConcurrentEffect, $Ctx](serviceImpl: $serviceNameFs2[F, $Ctx], f: $Metadata => Either[$Error, $Ctx]): $ServerServiceDefinition = {")
      .indent
      .newline
      .add(s"val g: $Metadata => F[$Ctx] = f(_).leftMap[Throwable]($FailedPrecondition.withDescription(_).asRuntimeException()).liftTo[F]")
      .newline
      .add(s"$ServerServiceDefinition")
      .call(serviceBindingImplementations)
      .outdent
      .add("}")
  }

  /// For those that prefer io.grpc.Metadata instead of parameterized context

  private[this] def serviceClientMeta: PrinterEndo =
    _.add(
      s"def stub[F[_]: $ConcurrentEffect](channel: $Channel, callOptions: $CallOptions = $CallOptions.DEFAULT): $serviceNameFs2[F, $Metadata] = {")
      .indent
      .add(s"client[F, $Metadata](channel, identity, _ => callOptions)")
      .outdent
      .add("}")

  private[this] def serviceBindingMeta: PrinterEndo = {
    _.add(
      s"def bindService[F[_]: $ConcurrentEffect](serviceImpl: $serviceNameFs2[F, $Metadata]): $ServerServiceDefinition = {")
      .indent
      .add(s"service[F, $Metadata](serviceImpl, _.asRight[$Error])")
      .outdent
      .add("}")
  }

  ///

  def printService(printer: FunctionalPrinter): FunctionalPrinter = {
    printer
      .add(s"package $servicePkgName", "", "import _root_.cats.implicits._", "")
      .call(serviceTrait)
      .newline
      .call(serviceObject)
  }
}

object Fs2GrpcServicePrinter {

  object constants {

    private val effPkg  = "_root_.cats.effect"
    private val grpcPkg = "_root_.io.grpc"
    private val jrtPkg  = "_root_.org.lyranthe.fs2_grpc.java_runtime"
    private val fs2Pkg  = "_root_.fs2"

    ///

    val Error                   = "String"
    val Ctx                     = "A"

    val ConcurrentEffect        = s"$effPkg.ConcurrentEffect"
    val Stream                  = s"$fs2Pkg.Stream"

    val Fs2ServerCallHandler    = s"$jrtPkg.server.Fs2ServerCallHandler"
    val Fs2ClientCall           = s"$jrtPkg.client.Fs2ClientCall"

    val ServerServiceDefinition = s"$grpcPkg.ServerServiceDefinition"
    val CallOptions             = s"$grpcPkg.CallOptions"
    val Channel                 = s"$grpcPkg.Channel"
    val Metadata                = s"$grpcPkg.Metadata"
    val FailedPrecondition      = s"$grpcPkg.Status.FAILED_PRECONDITION"

  }

}
