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

package fs2.grpc.e2e

import hello.world._
import io.grpc.inprocess._
import cats.effect._
import cats.implicits._
import munit._
import cats.effect.std.UUIDGen
import io.grpc._
import scala.jdk.CollectionConverters._
import cats.effect.std.Dispatcher
import fs2.grpc.server._
import fs2.grpc.client._
import fs2.grpc.syntax.all._
import cats.data._
import cats._
import cats.effect.std.SecureRandom

class AspectSpec extends CatsEffectSuite with CatsEffectFunFixtures {
  def startServices[F[_]](id: String)(xs: ServerServiceDefinition*)(implicit F: Sync[F]): Resource[F, Server] =
    InProcessServerBuilder
      .forName(id.toString())
      .addServices(xs.toList.asJava)
      .resource[F]
      .evalTap(s => F.delay(s.start()))

  def testConnection[F[_]: SecureRandom: Async, G[_], A, B](
      service: TestServiceFs2Grpc[G, A],
      serviceAspect: ServiceAspect[G, F, A],
      clientAspect: ClientAspect[G, F, B]
  ): Resource[F, TestServiceFs2Grpc[G, B]] =
    Dispatcher.parallel[F].flatMap { d =>
      Resource.eval(UUIDGen.fromSecureRandom[F].randomUUID).flatMap { id =>
        startServices[F](id.toString())(
          TestServiceFs2Grpc.serviceFull[G, F, A](
            d,
            service,
            serviceAspect,
            ServerOptions.default
          )
        ) >> InProcessChannelBuilder.forName(id.toString()).usePlaintext().resource[F].map { conn =>
          TestServiceFs2Grpc.mkClientFull[G, F, B](
            d,
            conn,
            clientAspect,
            ClientOptions.default
          )
        }
      }
    }

  test("tracing requests should work as expected") {
    case class TracingKey(value: String)
    case class Span(name: String, parent: Either[Span, Option[TracingKey]]) {
      def traceKey: Option[TracingKey] = parent.leftMap(_.traceKey).merge
    }
    case class SpanInfo(span: Span, messages: List[String])
    type WriteSpan[A] = WriterT[IO, List[SpanInfo], A]
    type Traced[A] = Kleisli[WriteSpan, Span, A]
    val Traced: Monad[Traced] = Monad[Traced]
    val liftK: IO ~> Traced = WriterT.liftK[IO, List[SpanInfo]] andThen Kleisli.liftK[WriteSpan, Span]
    def span[A](name: String)(fa: Traced[A]): Traced[A] =
      fa.local[Span](parent => Span(name, Left(parent)))

    def spanStream[A](name: String)(fa: fs2.Stream[Traced, A]): fs2.Stream[Traced, A] = {
      val current: Traced[Span] = Kleisli.ask
      fs2.Stream.eval(current).flatMap { parent =>
        fa.translate(new (Traced ~> Traced) {
          def apply[B](fa: Traced[B]): Traced[B] =
            Kleisli.local((_: Span) => Span(name, Left(parent)))(fa)
        })
      }
    }

    def tell(spanInfos: List[SpanInfo]): Traced[Unit] =
      Kleisli.liftF(WriterT.tell[IO, List[SpanInfo]](spanInfos))

    def log(msgs: String*): Traced[Unit] = Kleisli.ask[WriteSpan, Span].flatMap { current =>
      tell(List(SpanInfo(current, msgs.toList)))
    }

    val tracingHeaderKey = Metadata.Key.of("TRACE_KEY", Metadata.ASCII_STRING_MARSHALLER)
    def getTraceHeader(ctx: Metadata): Option[TracingKey] =
      Option(ctx.get(tracingHeaderKey)).map(TracingKey(_))

    def serializeTraceHeader(key: TracingKey): Metadata = {
      val m = new Metadata
      m.put(tracingHeaderKey, key.value)
      m
    }

    def getTracingHeader: Traced[Metadata] =
      Kleisli.ask[WriteSpan, Span].map { span =>
        span.traceKey.map(serializeTraceHeader).getOrElse(new Metadata)
      }

    val service = new TestServiceFs2Grpc[Traced, Metadata] {
      override def noStreaming(request: TestMessage, ctx: Metadata): Traced[TestMessage] =
        span("noStreaming") {
          log("noStreaming") >>
            Traced.pure(TestMessage.defaultInstance)
        }

      override def clientStreaming(request: fs2.Stream[Traced, TestMessage], ctx: Metadata): Traced[TestMessage] =
        span("clientStreaming") {
          log("clientStreaming") >>
            request.compile.last.map(_.getOrElse(TestMessage.defaultInstance))
        }

      override def serverStreaming(request: TestMessage, ctx: Metadata): fs2.Stream[Traced, TestMessage] =
        spanStream("serverStreaming") {
          fs2.Stream(request).repeatN(2).evalTap(_ => log("serverStreaming"))
        }

      override def bothStreaming(
          request: fs2.Stream[Traced, TestMessage],
          ctx: Metadata
      ): fs2.Stream[Traced, TestMessage] =
        spanStream("bothStreaming") {
          request.evalTap(_ => log("bothStreaming"))
        }
    }

    IO.ref(List.empty[SpanInfo]).flatMap { state =>
      def runRootTrace[A](cctx: ServiceCallContext[?, ?])(fa: Traced[A]): IO[A] = {
        val root = Span(cctx.methodDescriptor.getFullMethodName(), Right(getTraceHeader(cctx.metadata)))
        fa.run(root).run.flatMap { case (xs, a) =>
          state.update(_ ++ xs) as a
        }
      }

      def runRootTraceStreamed[A](cctx: ServiceCallContext[?, ?])(fa: fs2.Stream[Traced, A]): fs2.Stream[IO, A] =
        fa.translate(new (Traced ~> IO) {
          def apply[B](fa: Traced[B]): IO[B] = runRootTrace(cctx)(fa)
        })

      val tracingServiceAspect = new ServiceAspect[Traced, IO, Metadata] {
        override def visitUnaryToUnaryCallTrailers[Req, Res](
            callCtx: ServiceCallContext[Req, Res],
            req: Req,
            run: (Req, Metadata) => Traced[(Res, Metadata)]
        ): IO[(Res, Metadata)] = ???

        override def visitStreamingToUnaryCallTrailers[Req, Res](
            callCtx: ServiceCallContext[Req, Res],
            req: fs2.Stream[IO, Req],
            run: (fs2.Stream[Traced, Req], Metadata) => Traced[(Res, Metadata)]
        ): IO[(Res, Metadata)] = ???

        override def visitUnaryToUnaryCall[Req, Res](
            callCtx: ServiceCallContext[Req, Res],
            req: Req,
            run: (Req, Metadata) => Traced[Res]
        ): IO[Res] = runRootTrace(callCtx)(run(req, callCtx.metadata))

        override def visitUnaryToStreamingCall[Req, Res](
            callCtx: ServiceCallContext[Req, Res],
            req: Req,
            run: (Req, Metadata) => fs2.Stream[Traced, Res]
        ): fs2.Stream[IO, Res] = runRootTraceStreamed(callCtx)(run(req, callCtx.metadata))

        override def visitStreamingToUnaryCall[Req, Res](
            callCtx: ServiceCallContext[Req, Res],
            req: fs2.Stream[IO, Req],
            run: (fs2.Stream[Traced, Req], Metadata) => Traced[Res]
        ): IO[Res] = runRootTrace(callCtx)(run(req.translate(liftK), callCtx.metadata))

        override def visitStreamingToStreamingCall[Req, Res](
            callCtx: ServiceCallContext[Req, Res],
            req: fs2.Stream[IO, Req],
            run: (fs2.Stream[Traced, Req], Metadata) => fs2.Stream[Traced, Res]
        ): fs2.Stream[IO, Res] = runRootTraceStreamed(callCtx)(run(req.translate(liftK), callCtx.metadata))
      }

      val tracingClientAspect = new ClientAspect[Traced, IO, Unit] {
        override def visitUnaryToUnaryCallTrailers[Req, Res](
            callCtx: ClientCallContext[Req, Res, Unit],
            req: Req,
            run: (Req, Metadata) => IO[(Res, Metadata)]
        ): Traced[(Res, Metadata)] = ???

        override def visitStreamingToUnaryCallTrailers[Req, Res](
            callCtx: ClientCallContext[Req, Res, Unit],
            req: fs2.Stream[Traced, Req],
            run: (fs2.Stream[IO, Req], Metadata) => IO[(Res, Metadata)]
        ): Traced[(Res, Metadata)] = ???

        override def visitUnaryToUnaryCall[Req, Res](
            callCtx: ClientCallContext[Req, Res, Unit],
            req: Req,
            run: (Req, Metadata) => IO[Res]
        ): Traced[Res] =
          getTracingHeader.flatMap(md => liftK(run(req, md)))

        override def visitUnaryToStreamingCall[Req, Res](
            callCtx: ClientCallContext[Req, Res, Unit],
            req: Req,
            run: (Req, Metadata) => fs2.Stream[IO, Res]
        ): fs2.Stream[Traced, Res] =
          fs2.Stream.eval(getTracingHeader).flatMap(md => run(req, md).translate(liftK))

        override def visitStreamingToUnaryCall[Req, Res](
            callCtx: ClientCallContext[Req, Res, Unit],
            req: fs2.Stream[Traced, Req],
            run: (fs2.Stream[IO, Req], Metadata) => IO[Res]
        ): Traced[Res] = Kleisli.ask[WriteSpan, Span].flatMap { parent =>
          getTracingHeader.flatMap { md =>
            liftK(IO.ref(List.empty[SpanInfo])).flatMap { state =>
              val req2 = req.translate(new (Traced ~> IO) {
                def apply[A](fa: Traced[A]): IO[A] =
                  fa.run(parent).run.flatMap { case (xs, a) =>
                    state.update(_ ++ xs) as a
                  }
              })
              liftK(run(req2, md)) <* (liftK(state.get) >>= tell)
            }
          }
        }

        override def visitStreamingToStreamingCall[Req, Res](
            callCtx: ClientCallContext[Req, Res, Unit],
            req: fs2.Stream[Traced, Req],
            run: (fs2.Stream[IO, Req], Metadata) => fs2.Stream[IO, Res]
        ): fs2.Stream[Traced, Res] =
          fs2.Stream.eval(Kleisli.ask[WriteSpan, Span]).flatMap { parent =>
            fs2.Stream.eval(getTracingHeader).flatMap { md =>
              fs2.Stream.eval(liftK(IO.ref(List.empty[SpanInfo]))).flatMap { state =>
                val req2 = req.translate(new (Traced ~> IO) {
                  def apply[A](fa: Traced[A]): IO[A] =
                    fa.run(parent).run.flatMap { case (xs, a) =>
                      state.update(_ ++ xs) as a
                    }
                })
                run(req2, md).translate(liftK) ++ fs2.Stream.exec((liftK(state.get) >>= tell))
              }
            }
          }
      }

      testConnection[IO, Traced, Metadata, Unit](
        service,
        tracingServiceAspect,
        tracingClientAspect
      ).use { (client: TestServiceFs2Grpc[Traced, Unit]) =>
        def testWithKey(rootKey: Option[TracingKey] = None) = {
          def trackServer[A](fa: IO[A]): IO[List[SpanInfo]] =
            state.set(Nil) >> fa >> state.get

          def trackClient[A](traced: Traced[A]): IO[List[SpanInfo]] =
            traced.run(Span("root", Right(rootKey))).written

          def trackAndAssertServer[A](name: String, n: Int)(fa: IO[A])(implicit loc: Location): IO[Unit] =
            trackServer(fa).map { serverInfos =>
              assertEquals(serverInfos.size, n)
              serverInfos.foreach { si =>
                assertEquals(si.span.name, name)
                assert(clue(si.span.parent).isLeft, "is child")
                assertEquals(si.messages, List(name))
              }
            }

          def trackAndAssertClient[A](name: String, n: Int)(fa: Traced[A])(implicit loc: Location): IO[Unit] =
            trackClient(fa).map { clientInfos =>
              assertEquals(clientInfos.size, n)
              clientInfos.foreach { ci =>
                assertEquals(ci.span.name, s"client-${name}")
                assert(clue(ci.span.parent).isLeft, "is child")
                assertEquals(ci.messages, List(s"client-${name}"))
              }
            }

          val noStreaming = trackAndAssertServer("noStreaming", 1) {
            trackAndAssertClient("noStreaming", 1) {
              span("client-noStreaming") {
                log("client-noStreaming") >>
                  client.noStreaming(TestMessage.defaultInstance, ())
              }
            }
          }

          val clientStreaming = trackAndAssertServer("clientStreaming", 1) {
            trackAndAssertClient("clientStreaming", 2) {
              val req = fs2.Stream
                .eval {
                  span("client-clientStreaming") {
                    log("client-clientStreaming").as(TestMessage.defaultInstance)
                  }
                }
                .repeatN(2)

              client.clientStreaming(req, ())
            }
          }

          val serverStreaming = trackAndAssertServer("serverStreaming", 2) {
            trackAndAssertClient("serverStreaming", 1) {
              span("client-serverStreaming") {
                log("client-serverStreaming") >>
                  client.serverStreaming(TestMessage.defaultInstance, ()).compile.drain
              }
            }
          }

          val bothStreaming = trackAndAssertServer("bothStreaming", 2) {
            trackAndAssertClient("bothStreaming", 2) {
              val req = fs2.Stream
                .eval {
                  span("client-bothStreaming") {
                    log("client-bothStreaming").as(TestMessage.defaultInstance)
                  }
                }
                .repeatN(2)

              client.bothStreaming(req, ()).compile.drain
            }
          }

          noStreaming >> clientStreaming >> serverStreaming >> bothStreaming
        }

        testWithKey() >> testWithKey(Some(TracingKey("my_tracing_key")))
      }
    }
  }
}
