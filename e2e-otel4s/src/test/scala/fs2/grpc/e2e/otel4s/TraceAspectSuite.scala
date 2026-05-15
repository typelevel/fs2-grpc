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

package fs2.grpc.e2e.otel4s

import cats.effect.std.Dispatcher
import cats.effect.{Async, IO, Resource}
import fs2.Stream
import fs2.grpc.client.ClientOptions
import fs2.grpc.otel4s.trace.{TraceClientAspect, TraceServiceAspect}
import fs2.grpc.server.ServerOptions
import fs2.grpc.syntax.all._
import hello.world.test_service.{
  TestRequest,
  TestResponse,
  TestServiceFs2Grpc,
  TestServiceFs2GrpcTrailers,
  TestServiceGrpc
}
import io.grpc.inprocess.{InProcessChannelBuilder, InProcessServerBuilder}
import io.grpc.{Channel, Metadata, Server, ServerServiceDefinition, Status, StatusRuntimeException}
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import munit.{CatsEffectSuite, Location, TestOptions}
import org.typelevel.otel4s.oteljava.testkit.OtelJavaTestkit
import org.typelevel.otel4s.oteljava.testkit.trace.{
  SpanContextExpectation,
  SpanExpectation,
  TraceExpectation,
  TraceExpectations,
  TraceForestExpectation
}
import org.typelevel.otel4s.semconv.experimental.attributes.RpcExperimentalAttributes
import org.typelevel.otel4s.trace.{SpanContext, SpanKind, Tracer}
import org.typelevel.otel4s.{Attribute, Attributes}

import scala.concurrent.duration._

class TraceAspectSuite extends CatsEffectSuite {

  withFixture("follow request's span") { fixture =>
    for {
      rootSpanContext <- IO.deferred[SpanContext]
      response <- fixture.tracer.span("root").use { span =>
        rootSpanContext.complete(span.context) *> fixture.client.noStreaming(TestRequest(), new Metadata())
      }

      traceContext <- rootSpanContext.get

      _ <- fixture.assertTraces(
        expectedSuccessfulTrace(
          traceContext.traceIdHex,
          TestServiceGrpc.METHOD_NO_STREAMING.getFullMethodName,
          "internal-handler:noStreaming"
        )
      )
    } yield {
      // server middleware shouldn't inject tracing info into response metadata
      assertEquals(response._2.keys().size(), 0)
    }
  }

  withFixture("propagate the client span for server streaming calls") { fixture =>
    for {
      rootSpanContext <- IO.deferred[SpanContext]
      _ <- fixture.tracer.span("root").use { span =>
        rootSpanContext.complete(span.context) *>
          fixture.client.serverStreaming(TestRequest(), new Metadata()).compile.drain
      }

      traceContext <- rootSpanContext.get

      _ <- fixture.assertTraces(
        expectedSuccessfulTrace(
          traceContext.traceIdHex,
          TestServiceGrpc.METHOD_SERVER_STREAMING.getFullMethodName,
          "internal-handler:serverStreaming"
        )
      )
    } yield ()
  }

  withFixture("propagate the client span for bidirectional streaming calls") { fixture =>
    for {
      rootSpanContext <- IO.deferred[SpanContext]
      _ <- fixture.tracer.span("root").use { span =>
        rootSpanContext.complete(span.context) *>
          fixture.client.bothStreaming(Stream.emit(TestRequest()), new Metadata()).compile.drain
      }

      traceContext <- rootSpanContext.get

      _ <- fixture.assertTraces(
        expectedSuccessfulTrace(
          traceContext.traceIdHex,
          TestServiceGrpc.METHOD_BOTH_STREAMING.getFullMethodName,
          "internal-handler:bothStreaming"
        )
      )
    } yield ()
  }

  withFixture(
    "reflect config changes",
    TraceClientAspect.Config.default
      .withTracerName("client-tracer")
      .withAttributes((a, _) => Attributes(Attribute("client-operation", a.toString))),
    TraceServiceAspect.Config.default
      .withTracerName("service-tracer")
      .withAttributes((a, _) => Attributes(Attribute("service-operation", a.toString)))
  ) { fixture =>
    def expectedTraces(traceId: String): TraceForestExpectation =
      TraceForestExpectation.ordered(
        TraceExpectation.ordered(
          spanExpectation("root", Attributes.empty, traceId, SpanKind.Internal),
          TraceExpectation.ordered(
            spanExpectation(
              TestServiceGrpc.METHOD_NO_STREAMING.getFullMethodName,
              Attributes(
                Attribute("client-operation", "UnaryToUnaryCallTrailers"),
                RpcExperimentalAttributes.RpcResponseStatusCode("OK")
              ),
              traceId,
              SpanKind.Client
            ),
            TraceExpectation.ordered(
              spanExpectation(
                TestServiceGrpc.METHOD_NO_STREAMING.getFullMethodName,
                Attributes(
                  Attribute("service-operation", "UnaryToUnaryCall"),
                  RpcExperimentalAttributes.RpcResponseStatusCode("OK")
                ),
                traceId,
                SpanKind.Server
              ),
              TraceExpectation.leaf(
                spanExpectation(
                  "internal-handler:noStreaming",
                  Attributes.empty,
                  traceId,
                  SpanKind.Internal
                )
              )
            )
          )
        )
      )

    for {
      rootSpanContext <- IO.deferred[SpanContext]
      response <- fixture.tracer.span("root").use { span =>
        rootSpanContext.complete(span.context) *> fixture.client.noStreaming(TestRequest(), new Metadata())
      }

      traceContext <- rootSpanContext.get

      _ <- fixture.assertTraces(expectedTraces(traceContext.traceIdHex))
    } yield {
      // server middleware shouldn't inject tracing info into response metadata
      assertEquals(response._2.keys().size(), 0)
    }
  }

  withFixture(
    "include configured server address attributes",
    TraceClientAspect.Config.default.withServerAddress("client.example", Some(443)),
    TraceServiceAspect.Config.default.withServerAddress("service.example", Some(50051))
  ) { fixture =>
    for {
      rootSpanContext <- IO.deferred[SpanContext]
      response <- fixture.tracer.span("root").use { span =>
        rootSpanContext.complete(span.context) *> fixture.client.noStreaming(TestRequest(), new Metadata())
      }

      traceContext <- rootSpanContext.get

      _ <- fixture.assertTraces(
        expectedSuccessfulTrace(
          traceContext.traceIdHex,
          TestServiceGrpc.METHOD_NO_STREAMING.getFullMethodName,
          "internal-handler:noStreaming",
          clientAttributes = Attributes(Attribute("server.address", "client.example"), Attribute("server.port", 443L)),
          serviceAttributes =
            Attributes(Attribute("server.address", "service.example"), Attribute("server.port", 50051L))
        )
      )
    } yield {
      assertEquals(response._2.keys().size(), 0)
    }
  }

  withFixture(
    "include configured server address without port",
    TraceClientAspect.Config.default.withServerAddress("/run/client.sock", None),
    TraceServiceAspect.Config.default.withServerAddress("/run/service.sock", None)
  ) { fixture =>
    for {
      rootSpanContext <- IO.deferred[SpanContext]
      response <- fixture.tracer.span("root").use { span =>
        rootSpanContext.complete(span.context) *> fixture.client.noStreaming(TestRequest(), new Metadata())
      }

      traceContext <- rootSpanContext.get

      _ <- fixture.assertTraces(
        expectedSuccessfulTrace(
          traceContext.traceIdHex,
          TestServiceGrpc.METHOD_NO_STREAMING.getFullMethodName,
          "internal-handler:noStreaming",
          clientAttributes = Attributes(Attribute("server.address", "/run/client.sock")),
          serviceAttributes = Attributes(Attribute("server.address", "/run/service.sock"))
        )
      )
    } yield {
      assertEquals(response._2.keys().size(), 0)
    }
  }

  withFixture(
    "mark server error status codes as errors",
    serviceBehavior = ServiceBehavior.failNoStreaming(Status.INTERNAL)
  ) { fixture =>
    for {
      rootSpanContext <- IO.deferred[SpanContext]
      result <- fixture.tracer.span("root").use { span =>
        rootSpanContext.complete(span.context) *>
          fixture.client.noStreaming(TestRequest(), new Metadata()).attempt
      }

      traceContext <- rootSpanContext.get

      _ <- IO(assertEquals(result.left.map(_.getClass), Left(classOf[StatusRuntimeException])))
      _ <- fixture.assertTraces(
        expectedFailedTrace(
          traceContext.traceIdHex,
          TestServiceGrpc.METHOD_NO_STREAMING.getFullMethodName,
          Status.Code.INTERNAL.name(),
          clientError = true,
          serverError = true
        )
      )
    } yield ()
  }

  withFixture(
    "do not mark non-server error status codes as server span errors",
    serviceBehavior = ServiceBehavior.failNoStreaming(Status.INVALID_ARGUMENT)
  ) { fixture =>
    for {
      rootSpanContext <- IO.deferred[SpanContext]
      result <- fixture.tracer.span("root").use { span =>
        rootSpanContext.complete(span.context) *>
          fixture.client.noStreaming(TestRequest(), new Metadata()).attempt
      }

      traceContext <- rootSpanContext.get

      _ <- IO(assertEquals(result.left.map(_.getClass), Left(classOf[StatusRuntimeException])))
      _ <- fixture.assertTraces(
        expectedFailedTrace(
          traceContext.traceIdHex,
          TestServiceGrpc.METHOD_NO_STREAMING.getFullMethodName,
          Status.Code.INVALID_ARGUMENT.name(),
          clientError = true,
          serverError = false
        )
      )
    } yield ()
  }

  private def spanExpectation(
      name: String,
      attributes: Attributes,
      traceId: String,
      kind: SpanKind
  ): SpanExpectation =
    SpanExpectation
      .name(name)
      .kind(kind)
      .attributesExact(attributes)
      .spanContext(SpanContextExpectation.any.traceIdHex(traceId))

  private def expectedSuccessfulTrace(
      traceId: String,
      methodName: String,
      handlerSpanName: String,
      clientAttributes: Attributes = Attributes.empty,
      serviceAttributes: Attributes = Attributes.empty
  ): TraceForestExpectation = {
    val attributes = Attributes(
      RpcExperimentalAttributes.RpcSystemName(RpcExperimentalAttributes.RpcSystemValue.Grpc.value),
      RpcExperimentalAttributes.RpcMethod(methodName),
      RpcExperimentalAttributes.RpcResponseStatusCode("OK")
    )

    TraceForestExpectation.ordered(
      TraceExpectation.ordered(
        spanExpectation("root", Attributes.empty, traceId, SpanKind.Internal),
        TraceExpectation.ordered(
          spanExpectation(
            methodName,
            attributes ++ clientAttributes,
            traceId,
            SpanKind.Client
          ),
          TraceExpectation.ordered(
            spanExpectation(
              methodName,
              attributes ++ serviceAttributes,
              traceId,
              SpanKind.Server
            ),
            TraceExpectation.leaf(
              spanExpectation(
                handlerSpanName,
                Attributes.empty,
                traceId,
                SpanKind.Internal
              )
            )
          )
        )
      )
    )
  }

  private def expectedFailedTrace(
      traceId: String,
      methodName: String,
      code: String,
      clientError: Boolean,
      serverError: Boolean
  ): TraceForestExpectation = {
    val baseAttributes = Attributes(
      RpcExperimentalAttributes.RpcSystemName(RpcExperimentalAttributes.RpcSystemValue.Grpc.value),
      RpcExperimentalAttributes.RpcMethod(methodName),
      RpcExperimentalAttributes.RpcResponseStatusCode(code)
    )
    val clientAttributes =
      if (clientError) baseAttributes ++ Attributes(Attribute("error.type", code)) else baseAttributes
    val serverAttributes =
      if (serverError) baseAttributes ++ Attributes(Attribute("error.type", code)) else baseAttributes

    TraceForestExpectation.ordered(
      TraceExpectation.ordered(
        spanExpectation("root", Attributes.empty, traceId, SpanKind.Internal),
        TraceExpectation.ordered(
          spanExpectation(
            methodName,
            clientAttributes,
            traceId,
            SpanKind.Client
          ),
          TraceExpectation.leaf(
            spanExpectation(
              methodName,
              serverAttributes,
              traceId,
              SpanKind.Server
            )
          )
        )
      )
    )
  }

  private def withFixture[A](
      opts: TestOptions,
      clientConfig: TraceClientAspect.Config = TraceClientAspect.Config.default,
      serviceConfig: TraceServiceAspect.Config = TraceServiceAspect.Config.default,
      serviceBehavior: ServiceBehavior = ServiceBehavior.default
  )(f: Fix => IO[A])(implicit loc: Location): Unit =
    test(opts) {
      mkFixture(clientConfig, serviceConfig, serviceBehavior).use(f)
    }

  private def mkFixture(
      clientConfig: TraceClientAspect.Config,
      serviceConfig: TraceServiceAspect.Config,
      serviceBehavior: ServiceBehavior
  ): Resource[IO, Fix] =
    for {
      testkit <- OtelJavaTestkit.builder[IO].addTextMapPropagators(W3CTraceContextPropagator.getInstance()).build

      dispatcher <- Dispatcher.parallel[IO]

      tracerProvider = testkit.tracerProvider

      serviceAspect <- TraceServiceAspect.create[IO](serviceConfig)(Async[IO], tracerProvider).toResource
      clientAspect <- TraceClientAspect.create[IO](clientConfig)(Async[IO], tracerProvider).toResource

      tracer <- testkit.tracerProvider.get("service").toResource

      serviceDefinition = TestServiceFs2Grpc.serviceFull(
        dispatcher,
        new TestService(serviceBehavior)(tracer),
        serviceAspect,
        ServerOptions.default
      )

      id <- IO.randomUUID.map(_.toString).toResource

      _ <- startServices(id)(serviceDefinition)

      channel <- bindClientChannel(id)

      client = TestServiceFs2GrpcTrailers.mkClientFull(
        dispatcher,
        channel,
        clientAspect,
        ClientOptions.default
      )
    } yield new Fix(client, testkit, tracer)

  private final class Fix(
      val client: TestServiceFs2GrpcTrailers[IO, Metadata],
      val testkit: OtelJavaTestkit[IO],
      val tracer: Tracer[IO]
  ) {
    def assertTraces(expectation: TraceForestExpectation)(implicit loc: Location): IO[Unit] =
      testkit.finishedSpans.flatMap { spans =>
        TraceExpectations.check(spans, expectation) match {
          case Right(_) =>
            IO.unit
          case Left(mismatches) =>
            IO(fail(TraceExpectations.format(mismatches)))
        }
      }

  }

  sealed trait Op
  object Op {
    case object NoStreaming extends Op
    case object ClientStreaming extends Op
    case object ServerStreaming extends Op
    case object BothStreaming extends Op
  }

  case class ServerEvent(op: Op, ctx: Metadata)

  private case class ServiceBehavior(noStreamingFailure: Option[Status])

  private object ServiceBehavior {
    val default: ServiceBehavior =
      ServiceBehavior(None)

    def failNoStreaming(status: Status): ServiceBehavior =
      ServiceBehavior(Some(status))
  }

  private class TestService(behavior: ServiceBehavior)(implicit T: Tracer[IO])
      extends TestServiceFs2Grpc[IO, Metadata] {
    def noStreaming(request: TestRequest, ctx: Metadata): IO[TestResponse] =
      behavior.noStreamingFailure match {
        case Some(status) =>
          IO.raiseError(status.asRuntimeException())
        case None =>
          T.span("internal-handler:noStreaming").surround {
            IO.pure(TestResponse())
          }
      }

    def clientStreaming(request: Stream[IO, TestRequest], ctx: Metadata): IO[TestResponse] =
      T.span("internal-handler:clientStreaming").surround {
        IO.pure(TestResponse())
      }

    def serverStreaming(request: TestRequest, ctx: Metadata): Stream[IO, TestResponse] =
      Stream.eval {
        T.span("internal-handler:serverStreaming").surround {
          IO.pure(TestResponse())
        }
      }

    def bothStreaming(request: Stream[IO, TestRequest], ctx: Metadata): Stream[IO, TestResponse] =
      Stream.eval {
        T.span("internal-handler:bothStreaming").surround {
          IO.pure(TestResponse())
        }
      }
  }

  private def startServices(id: String)(xs: ServerServiceDefinition): Resource[IO, Server] =
    InProcessServerBuilder
      .forName(id)
      .addService(xs)
      .resource[IO](30.seconds)
      .evalTap(s => IO.delay(s.start()))

  private def bindClientChannel(id: String): Resource[IO, Channel] =
    InProcessChannelBuilder.forName(id).usePlaintext().resource[IO](30.seconds)

}
