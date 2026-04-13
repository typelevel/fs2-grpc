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
import fs2.grpc.client._
import io.grpc.Metadata
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.context.propagation.TextMapUpdater
import org.typelevel.otel4s.trace.{SpanFinalizer, SpanKind, Tracer, TracerProvider}
import fs2.grpc.BuildInfo
import org.typelevel.otel4s.semconv.attributes.ServerAttributes

private class TraceClientAspect[F[_]: MonadCancelThrow: Tracer](
    spanName: (AspectOperation, ClientCallContext[?, ?, Metadata]) => String,
    attributes: (AspectOperation, ClientCallContext[?, ?, Metadata]) => Attributes,
    finalizationStrategy: (AspectOperation, ClientCallContext[?, ?, Metadata]) => SpanFinalizer.Strategy
)(implicit textMapUpdater: TextMapUpdater[Metadata])
    extends ClientAspect[F, F, Metadata] {

  override def visitUnaryToUnaryCallTrailers[Req, Res](
      callCtx: ClientCallContext[Req, Res, Metadata],
      req: Req,
      run: (Req, Metadata) => F[(Res, Metadata)]
  ): F[(Res, Metadata)] =
    propagate(AspectOperation.UnaryToUnaryCallTrailers, callCtx, metadata => run(req, metadata))

  override def visitStreamingToUnaryCallTrailers[Req, Res](
      callCtx: ClientCallContext[Req, Res, Metadata],
      req: Stream[F, Req],
      run: (Stream[F, Req], Metadata) => F[(Res, Metadata)]
  ): F[(Res, Metadata)] =
    propagate(AspectOperation.StreamingToUnaryCallTrailers, callCtx, metadata => run(req, metadata))

  override def visitUnaryToUnaryCall[Req, Res](
      callCtx: ClientCallContext[Req, Res, Metadata],
      req: Req,
      run: (Req, Metadata) => F[Res]
  ): F[Res] =
    propagate(AspectOperation.UnaryToUnaryCall, callCtx, metadata => run(req, metadata))

  override def visitUnaryToStreamingCall[Req, Res](
      callCtx: ClientCallContext[Req, Res, Metadata],
      req: Req,
      run: (Req, Metadata) => Stream[F, Res]
  ): Stream[F, Res] =
    Stream
      .resource(span(AspectOperation.UnaryToStreamingCall, callCtx).resource)
      .flatMap { res =>
        Stream
          .eval(propagate(callCtx))
          .flatMap(metadata => run(req, metadata))
          .translate(res.trace)
      }

  override def visitStreamingToUnaryCall[Req, Res](
      callCtx: ClientCallContext[Req, Res, Metadata],
      req: Stream[F, Req],
      run: (Stream[F, Req], Metadata) => F[Res]
  ): F[Res] =
    propagate(AspectOperation.StreamingToUnaryCall, callCtx, metadata => run(req, metadata))

  override def visitStreamingToStreamingCall[Req, Res](
      callCtx: ClientCallContext[Req, Res, Metadata],
      req: Stream[F, Req],
      run: (Stream[F, Req], Metadata) => Stream[F, Res]
  ): Stream[F, Res] =
    Stream
      .resource(span(AspectOperation.StreamingToStreamingCall, callCtx).resource)
      .flatMap { res =>
        Stream
          .eval(propagate(callCtx))
          .flatMap(metadata => run(req, metadata))
          .translate(res.trace)
      }

  private def propagate[A](
      operation: AspectOperation,
      callCtx: ClientCallContext[?, ?, Metadata],
      fa: Metadata => F[A]
  ): F[A] =
    MonadCancelThrow[F].uncancelable { poll =>
      span(operation, callCtx).surround {
        propagate(callCtx).flatMap(metadata => poll(fa(metadata)))
      }
    }

  private def propagate(callCtx: ClientCallContext[?, ?, Metadata]): F[Metadata] =
    Tracer[F].propagate(new Metadata()).map { metadata =>
      metadata.merge(callCtx.ctx)
      metadata
    }

  private def span(operation: AspectOperation, ctx: ClientCallContext[?, ?, Metadata]) =
    Tracer[F]
      .spanBuilder(spanName(operation, ctx))
      .addAttributes(attributes(operation, ctx))
      .withSpanKind(SpanKind.Client)
      .withFinalizationStrategy(finalizationStrategy(operation, ctx))
      .build
}

object TraceClientAspect {

  /** Configuration for client tracing.
    *
    * The default configuration follows OpenTelemetry gRPC semantic conventions for the attributes this aspect can
    * determine from fs2-grpc call context.
    *
    * Use [[withServerAddress]] to add `server.address` and optional `server.port`, since the gRPC channel target is not
    * available to the aspect.
    *
    * @example
    *   {{{
    * val config = TraceClientAspect.Config.default.withServerAddress("grpc.io", Some(50051))
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

    /** Text map updater used to inject context into outgoing gRPC metadata. */
    def textMapUpdater: TextMapUpdater[Metadata]

    /** Function used to name client spans. */
    def spanName: (AspectOperation, ClientCallContext[?, ?, Metadata]) => String

    /** Function used to add attributes when client spans are created. */
    def attributes: (AspectOperation, ClientCallContext[?, ?, Metadata]) => Attributes

    /** Strategy used to record status, errors, and exceptions when client spans end. */
    def finalizationStrategy: (AspectOperation, ClientCallContext[?, ?, Metadata]) => SpanFinalizer.Strategy

    /** Sets the instrumentation scope name used to obtain the tracer. */
    def withTracerName(tracerName: String): Config

    /** Sets the text map updater used to inject context into outgoing gRPC metadata. */
    def withTextMapUpdater(textMapUpdater: TextMapUpdater[Metadata]): Config

    /** Sets the span name function.
      *
      * The default is the full gRPC method name, for example `com.example.EchoService/Echo`.
      */
    def withSpanName(spanName: (AspectOperation, ClientCallContext[?, ?, Metadata]) => String): Config

    /** Replaces the span attribute function.
      *
      * You must include `rpc.system.name` and `rpc.method` manually when replacing the defaults.
      *
      * @example
      *   {{{
      * val config =
      *   TraceClientAspect.Config.default.withAttributes { (_, ctx) =>
      *     Attributes(
      *       CommonAttributes.rpcSystem,
      *       CommonAttributes.rpcMethod(ctx.methodDescriptor.getFullMethodName)
      *     )
      *   }
      *   }}}
      */
    def withAttributes(attributes: (AspectOperation, ClientCallContext[?, ?, Metadata]) => Attributes): Config

    /** Adds `server.address` and, when provided, `server.port` to client spans.
      *
      * Use values derived from the gRPC channel target configuration, not connection-level peer information.
      *
      * @example
      *   {{{
      * val withPort = TraceClientAspect.Config.default.withServerAddress("grpc.io", Some(50051))
      * val socket = TraceClientAspect.Config.default.withServerAddress("/run/service.sock", None)
      *   }}}
      */
    def withServerAddress(serverAddress: String, serverPort: Option[Int]): Config

    /** Sets the span finalization strategy used to record status, errors, and exceptions. */
    def withFinalizationStrategy(
        finalizationStrategy: (AspectOperation, ClientCallContext[?, ?, Metadata]) => SpanFinalizer.Strategy
    ): Config
  }

  object Config {

    object Defaults {
      val tracerName: String = "fs2.grpc"

      val textMapUpdater: TextMapUpdater[Metadata] = TextMapUpdaters.asciiStringMetadataTextMapUpdater

      val spanName: (AspectOperation, ClientCallContext[?, ?, Metadata]) => String =
        (_, ctx) => ctx.methodDescriptor.getFullMethodName

      val attributes: (AspectOperation, ClientCallContext[?, ?, Metadata]) => Attributes =
        (_, ctx) =>
          Attributes(
            CommonAttributes.rpcSystem,
            CommonAttributes.rpcMethod(ctx.methodDescriptor.getFullMethodName)
          )

      val finalizationStrategy: (AspectOperation, ClientCallContext[?, ?, Metadata]) => SpanFinalizer.Strategy =
        (_, _) => GrpcSpanFinalizers.client
    }

    /** Default client tracing configuration.
      *
      * Emits `rpc.system.name`, `rpc.method`, and `rpc.response.status_code`, names spans with the full gRPC method
      * name, injects context into ASCII gRPC metadata, and classifies gRPC client errors.
      */
    def default: Config = {
      ConfigImpl(
        Defaults.tracerName,
        Defaults.textMapUpdater,
        Defaults.spanName,
        Defaults.attributes,
        Defaults.finalizationStrategy
      )
    }

    private final case class ConfigImpl(
        tracerName: String,
        textMapUpdater: TextMapUpdater[Metadata],
        spanName: (AspectOperation, ClientCallContext[?, ?, Metadata]) => String,
        attributes: (AspectOperation, ClientCallContext[?, ?, Metadata]) => Attributes,
        finalizationStrategy: (AspectOperation, ClientCallContext[?, ?, Metadata]) => SpanFinalizer.Strategy
    ) extends Config {
      def withTracerName(tracerName: String): Config =
        copy(tracerName = tracerName)

      def withTextMapUpdater(textMapUpdater: TextMapUpdater[Metadata]): Config =
        copy(textMapUpdater = textMapUpdater)

      def withSpanName(spanName: (AspectOperation, ClientCallContext[?, ?, Metadata]) => String): Config =
        copy(spanName = spanName)

      def withAttributes(attributes: (AspectOperation, ClientCallContext[?, ?, Metadata]) => Attributes): Config =
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
          finalizationStrategy: (AspectOperation, ClientCallContext[?, ?, Metadata]) => SpanFinalizer.Strategy
      ): Config =
        copy(finalizationStrategy = finalizationStrategy)
    }

  }

  /** Creates a client tracing aspect using [[Config.default]].
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
  def create[F[_]: MonadCancelThrow: TracerProvider]: F[ClientAspect[F, F, Metadata]] =
    create(Config.default)

  /** Creates a client tracing aspect using the supplied configuration.
    *
    * @see
    *   [[https://opentelemetry.io/docs/specs/semconv/rpc/grpc/ OpenTelemetry Semantic conventions for gRPC]]
    *
    * @see
    *   [[https://opentelemetry.io/docs/specs/semconv/rpc/rpc-spans OpenTelemetry Semantic conventions for RPC spans]]
    */
  def create[F[_]: MonadCancelThrow: TracerProvider](config: Config): F[ClientAspect[F, F, Metadata]] =
    TracerProvider[F].tracer(config.tracerName).withVersion(BuildInfo.version).get.map { implicit tracer =>
      implicit val textMapUpdater: TextMapUpdater[Metadata] = config.textMapUpdater

      new TraceClientAspect[F](
        config.spanName,
        config.attributes,
        config.finalizationStrategy
      )
    }

}
