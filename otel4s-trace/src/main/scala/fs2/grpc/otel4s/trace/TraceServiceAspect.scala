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

package fs2.grpc.otel4s.trace

import cats.effect.MonadCancelThrow
import cats.syntax.all._
import fs2.Stream
import fs2.grpc.server._
import io.grpc.Metadata
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.context.propagation.TextMapGetter
import org.typelevel.otel4s.trace._
import fs2.grpc.BuildInfo
import org.typelevel.otel4s.semconv.attributes.ServerAttributes

import scala.util.chaining._

private class TraceServiceAspect[F[_]: MonadCancelThrow: Tracer](
    spanName: (AspectOperation, ServiceCallContext[?, ?]) => String,
    attributes: (AspectOperation, ServiceCallContext[?, ?]) => Attributes,
    finalizationStrategy: (AspectOperation, ServiceCallContext[?, ?]) => SpanFinalizer.Strategy
)(implicit textMapGetter: TextMapGetter[Metadata])
    extends ServiceAspect[F, F, Metadata] {

  def visitUnaryToUnaryCall[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: Req,
      run: (Req, Metadata) => F[Res]
  ): F[Res] =
    joinOrRoot(AspectOperation.UnaryToUnaryCall, callCtx, run(req, callCtx.metadata))

  def visitUnaryToStreamingCall[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: Req,
      run: (Req, Metadata) => Stream[F, Res]
  ): Stream[F, Res] =
    Stream.eval(Tracer[F].joinOrRoot(callCtx.metadata)(Tracer[F].currentSpanContext)).flatMap { ctx =>
      Stream.resource(span(AspectOperation.UnaryToStreamingCall, callCtx, ctx).resource).flatMap { res =>
        run(req, callCtx.metadata).translate(res.trace)
      }
    }

  def visitStreamingToUnaryCall[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: Stream[F, Req],
      run: (Stream[F, Req], Metadata) => F[Res]
  ): F[Res] =
    joinOrRoot(AspectOperation.StreamingToUnaryCall, callCtx, run(req, callCtx.metadata))

  def visitStreamingToStreamingCall[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: Stream[F, Req],
      run: (Stream[F, Req], Metadata) => Stream[F, Res]
  ): Stream[F, Res] =
    Stream.eval(Tracer[F].joinOrRoot(callCtx.metadata)(Tracer[F].currentSpanContext)).flatMap { ctx =>
      Stream.resource(span(AspectOperation.StreamingToStreamingCall, callCtx, ctx).resource).flatMap { res =>
        run(req, callCtx.metadata).translate(res.trace)
      }
    }

  def visitUnaryToUnaryCallTrailers[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: Req,
      run: (Req, Metadata) => F[(Res, Metadata)]
  ): F[(Res, Metadata)] =
    joinOrRoot(AspectOperation.UnaryToUnaryCallTrailers, callCtx, run(req, callCtx.metadata))

  def visitStreamingToUnaryCallTrailers[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: Stream[F, Req],
      run: (Stream[F, Req], Metadata) => F[(Res, Metadata)]
  ): F[(Res, Metadata)] =
    joinOrRoot(AspectOperation.StreamingToUnaryCallTrailers, callCtx, run(req, callCtx.metadata))

  private def joinOrRoot[A](operation: AspectOperation, callCtx: ServiceCallContext[?, ?], fa: => F[A]): F[A] =
    MonadCancelThrow[F].uncancelable { poll =>
      Tracer[F].joinOrRoot(callCtx.metadata) {
        span(operation, callCtx, None).surround(poll(fa))
      }
    }

  private def span(operation: AspectOperation, ctx: ServiceCallContext[?, ?], parent: Option[SpanContext]) =
    Tracer[F]
      .spanBuilder(spanName(operation, ctx))
      .addAttributes(attributes(operation, ctx))
      .withSpanKind(SpanKind.Server)
      .withFinalizationStrategy(finalizationStrategy(operation, ctx))
      .pipe(b => parent.fold(b)(b.withParent(_)))
      .build

}

object TraceServiceAspect {

  /** Configuration for service tracing.
    *
    * The default configuration follows OpenTelemetry gRPC semantic conventions for the attributes this aspect can
    * determine from fs2-grpc call context.
    *
    * Use [[withServerAddress]] to add `server.address` and optional `server.port`, since the server bind address is not
    * available to the aspect.
    *
    * @example
    *   {{{
    * val config =
    *   TraceServiceAspect.Config.default
    *     .withSpanName((_, ctx) => ctx.methodDescriptor.getFullMethodName)
    *   }}}
    *
    * @see
    *   [[https://opentelemetry.io/docs/specs/semconv/rpc/grpc/ OpenTelemetry Semantic conventions for gRPC]]
    *
    * @see
    *   [[https://opentelemetry.io/docs/specs/semconv/rpc/rpc-spans OpenTelemetry Semantic conventions for RPC spans]]
    */
  trait Config {

    /** Instrumentation scope name used to obtain the tracer. */
    def tracerName: String

    /** Text map getter used to extract context from incoming gRPC metadata. */
    def textMapGetter: TextMapGetter[Metadata]

    /** Function used to name service spans. */
    def spanName: (AspectOperation, ServiceCallContext[?, ?]) => String

    /** Function used to add attributes when service spans are created. */
    def attributes: (AspectOperation, ServiceCallContext[?, ?]) => Attributes

    /** Strategy used to record status, errors, and exceptions when service spans end. */
    def finalizationStrategy: (AspectOperation, ServiceCallContext[?, ?]) => SpanFinalizer.Strategy

    /** Sets the instrumentation scope name used to obtain the tracer. */
    def withTracerName(tracerName: String): Config

    /** Sets the text map getter used to extract context from incoming gRPC metadata. */
    def withTextMapGetter(textMapGetter: TextMapGetter[Metadata]): Config

    /** Sets the span name function.
      *
      * The default is the full gRPC method name, for example `com.example.EchoService/Echo`.
      */
    def withSpanName(spanName: (AspectOperation, ServiceCallContext[?, ?]) => String): Config

    /** Replaces the span attribute function.
      *
      * You must include `rpc.system.name` and `rpc.method` manually when replacing the defaults.
      *
      * @example
      *   {{{
      * val config =
      *   TraceServiceAspect.Config.default.withAttributes { (_, ctx) =>
      *     Attributes(
      *       CommonAttributes.rpcSystem,
      *       CommonAttributes.rpcMethod(ctx.methodDescriptor.getFullMethodName)
      *     )
      *   }
      *   }}}
      */
    def withAttributes(attributes: (AspectOperation, ServiceCallContext[?, ?]) => Attributes): Config

    /** Adds `server.address` and, when provided, `server.port` to service spans.
      *
      * Use values derived from the logical server address, not connection-level peer information.
      *
      * @example
      *   {{{
      * val withPort = TraceServiceAspect.Config.default.withServerAddress("grpc.io", Some(50051))
      * val socket = TraceServiceAspect.Config.default.withServerAddress("/run/service.sock", None)
      *   }}}
      */
    def withServerAddress(serverAddress: String, serverPort: Option[Int]): Config

    /** Sets the span finalization strategy used to record status, errors, and exceptions. */
    def withFinalizationStrategy(
        finalizationStrategy: (AspectOperation, ServiceCallContext[?, ?]) => SpanFinalizer.Strategy
    ): Config
  }

  object Config {

    object Defaults {
      val tracerName: String = "fs2.grpc"

      val textMapGetter: TextMapGetter[Metadata] = TextMapGetters.asciiStringMetadataTextMapGetter

      val spanName: (AspectOperation, ServiceCallContext[?, ?]) => String =
        (_, ctx) => ctx.methodDescriptor.getFullMethodName

      val attributes: (AspectOperation, ServiceCallContext[?, ?]) => Attributes =
        (_, ctx) =>
          Attributes(
            CommonAttributes.rpcSystem,
            CommonAttributes.rpcMethod(ctx.methodDescriptor.getFullMethodName)
          )

      val finalizationStrategy: (AspectOperation, ServiceCallContext[?, ?]) => SpanFinalizer.Strategy =
        (_, _) => GrpcSpanFinalizers.server
    }

    /** Default service tracing configuration.
      *
      * Emits `rpc.system.name`, `rpc.method`, and `rpc.response.status_code`, names spans with the full gRPC method
      * name, extracts context from ASCII gRPC metadata, and classifies gRPC server errors.
      */
    def default: Config = {
      ConfigImpl(
        Defaults.tracerName,
        Defaults.textMapGetter,
        Defaults.spanName,
        Defaults.attributes,
        Defaults.finalizationStrategy
      )
    }

    private final case class ConfigImpl(
        tracerName: String,
        textMapGetter: TextMapGetter[Metadata],
        spanName: (AspectOperation, ServiceCallContext[?, ?]) => String,
        attributes: (AspectOperation, ServiceCallContext[?, ?]) => Attributes,
        finalizationStrategy: (AspectOperation, ServiceCallContext[?, ?]) => SpanFinalizer.Strategy
    ) extends Config {
      def withTracerName(tracerName: String): Config =
        copy(tracerName = tracerName)

      def withTextMapGetter(textMapGetter: TextMapGetter[Metadata]): Config =
        copy(textMapGetter = textMapGetter)

      def withSpanName(
          spanName: (AspectOperation, ServiceCallContext[?, ?]) => String
      ): Config =
        copy(spanName = spanName)

      def withAttributes(
          attributes: (AspectOperation, ServiceCallContext[?, ?]) => Attributes
      ): Config =
        copy(attributes = attributes)

      def withServerAddress(serverAddress: String, serverPort: Option[Int]): Config =
        copy(attributes =
          (operation, ctx) =>
            attributes(operation, ctx) ++ serverPort.fold(
              Attributes(ServerAttributes.ServerAddress(serverAddress))
            ) { port =>
              Attributes(
                ServerAttributes.ServerAddress(serverAddress),
                ServerAttributes.ServerPort(port.toLong)
              )
            }
        )

      def withFinalizationStrategy(
          finalizationStrategy: (AspectOperation, ServiceCallContext[?, ?]) => SpanFinalizer.Strategy
      ): Config =
        copy(finalizationStrategy = finalizationStrategy)
    }

  }

  /** Creates a service tracing aspect using [[Config.default]].
    *
    * Defaults follow the OpenTelemetry gRPC semantic conventions for span kind, span name, `rpc.system.name`,
    * `rpc.method`, and `rpc.response.status_code`.
    *
    * @see
    *   [[https://opentelemetry.io/docs/specs/semconv/rpc/grpc/ OpenTelemetry Semantic conventions for gRPC]]
    *
    * @see
    *   [[https://opentelemetry.io/docs/specs/semconv/rpc/rpc-spans OpenTelemetry Semantic conventions for RPC spans]]
    */
  def create[F[_]: MonadCancelThrow: TracerProvider]: F[ServiceAspect[F, F, Metadata]] =
    create(Config.default)

  /** Creates a service tracing aspect using the supplied configuration.
    *
    * @see
    *   [[https://opentelemetry.io/docs/specs/semconv/rpc/grpc/ OpenTelemetry Semantic conventions for gRPC]]
    *
    * @see
    *   [[https://opentelemetry.io/docs/specs/semconv/rpc/rpc-spans OpenTelemetry Semantic conventions for RPC spans]]
    */
  def create[F[_]: MonadCancelThrow: TracerProvider](config: Config): F[ServiceAspect[F, F, Metadata]] =
    TracerProvider[F].tracer(config.tracerName).withVersion(BuildInfo.version).get.map { implicit tracer =>
      implicit val textMapGetter: TextMapGetter[Metadata] = config.textMapGetter

      new TraceServiceAspect[F](
        config.spanName,
        config.attributes,
        config.finalizationStrategy
      )
    }

}
