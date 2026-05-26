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

package fs2.grpc.server

import cats.arrow.FunctionK
import cats.effect.IO
import cats.~>
import fs2.Stream
import io.grpc.{Metadata, MethodDescriptor}

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.ListBuffer

class ServiceAspectMiddlewareSuite extends munit.CatsEffectSuite {

  import ServiceAspectMiddleware._

  test("compose should apply outer middleware's error handler after inner's on error") {
    val events = ListBuffer.empty[String]

    val outer = new ServiceAspectMiddleware[IO, Unit] {
      def unary[Req, Res](callCtx: ServiceCallContext[Req, Res], ctx: Unit): IO ~> IO =
        new (IO ~> IO) {
          def apply[A](fa: IO[A]): IO[A] =
            fa.onError { case _ => IO(events += "outer-error").void }
        }

      def streaming[Req, Res](callCtx: ServiceCallContext[Req, Res], ctx: Unit): Stream[IO, *] ~> Stream[IO, *] =
        FunctionK.id
    }

    val inner = new ServiceAspectMiddleware[IO, Unit] {
      def unary[Req, Res](callCtx: ServiceCallContext[Req, Res], ctx: Unit): IO ~> IO =
        new (IO ~> IO) {
          def apply[A](fa: IO[A]): IO[A] =
            fa.onError { case _ => IO(events += "inner-error").void }
        }

      def streaming[Req, Res](callCtx: ServiceCallContext[Req, Res], ctx: Unit): Stream[IO, *] ~> Stream[IO, *] =
        FunctionK.id
    }

    val composed = outer.compose(inner)
    val callCtx = mockServiceCallContext()

    composed
      .unary(callCtx, ())(IO.raiseError[Int](new RuntimeException("test error")))
      .attempt
      .map(r => assertEquals(r.isLeft, true)) *> IO(assertEquals(events.toList, List("inner-error", "outer-error")))
  }

  test("identity should not modify effects") {
    val middleware = ServiceAspectMiddleware.identity[IO, Unit]
    val callCtx = mockServiceCallContext()

    middleware.unary(callCtx, ())(IO.pure(42)).map(r => assertEquals(r, 42))
  }

  test("identity should not modify streams") {
    val middleware = ServiceAspectMiddleware.identity[IO, Unit]
    val callCtx = mockServiceCallContext()

    middleware.streaming(callCtx, ())(Stream(1, 2, 3)).compile.toList.map(r => assertEquals(r, List(1, 2, 3)))
  }

  test("compose should run middlewares in order") {
    val events = ListBuffer.empty[String]

    val first = new ServiceAspectMiddleware[IO, Unit] {
      def unary[Req, Res](callCtx: ServiceCallContext[Req, Res], ctx: Unit): IO ~> IO =
        new (IO ~> IO) {
          def apply[A](fa: IO[A]): IO[A] =
            IO(events += "first-before") *> fa <* IO(events += "first-after")
        }

      def streaming[Req, Res](callCtx: ServiceCallContext[Req, Res], ctx: Unit): Stream[IO, *] ~> Stream[IO, *] =
        new (Stream[IO, *] ~> Stream[IO, *]) {
          def apply[A](s: Stream[IO, A]): Stream[IO, A] =
            Stream
              .eval(IO(events += "first-stream-before")) >> s ++ Stream.eval(IO(events += "first-stream-after")).drain
        }
    }

    val second = new ServiceAspectMiddleware[IO, Unit] {
      def unary[Req, Res](callCtx: ServiceCallContext[Req, Res], ctx: Unit): IO ~> IO =
        new (IO ~> IO) {
          def apply[A](fa: IO[A]): IO[A] =
            IO(events += "second-before") *> fa <* IO(events += "second-after")
        }

      def streaming[Req, Res](callCtx: ServiceCallContext[Req, Res], ctx: Unit): Stream[IO, *] ~> Stream[IO, *] =
        new (Stream[IO, *] ~> Stream[IO, *]) {
          def apply[A](s: Stream[IO, A]): Stream[IO, A] =
            Stream.eval(IO(events += "second-stream-before")) >> s ++ Stream
              .eval(IO(events += "second-stream-after"))
              .drain
        }
    }

    val composed = first.compose(second)
    val callCtx = mockServiceCallContext()

    composed.unary(callCtx, ())(IO.pure(42)).map { r =>
      assertEquals(r, 42)
      assertEquals(events.toList, List("first-before", "second-before", "second-after", "first-after"))
    }
  }

  test("composeAll should combine multiple middlewares") {
    val counter = new AtomicReference(0)

    def countingMiddleware(n: Int): ServiceAspectMiddleware[IO, Unit] =
      new ServiceAspectMiddleware[IO, Unit] {
        def unary[Req, Res](callCtx: ServiceCallContext[Req, Res], ctx: Unit): IO ~> IO =
          new (IO ~> IO) {
            def apply[A](fa: IO[A]): IO[A] =
              IO(counter.updateAndGet(_ + n)) *> fa
          }

        def streaming[Req, Res](callCtx: ServiceCallContext[Req, Res], ctx: Unit): Stream[IO, *] ~> Stream[IO, *] =
          new (Stream[IO, *] ~> Stream[IO, *]) {
            def apply[A](s: Stream[IO, A]): Stream[IO, A] =
              Stream.eval(IO(counter.updateAndGet(_ + n))) >> s
          }
      }

    val combined = ServiceAspectMiddleware.composeAll(
      countingMiddleware(1),
      countingMiddleware(10),
      countingMiddleware(100)
    )
    val callCtx = mockServiceCallContext()

    combined.unary(callCtx, ())(IO.pure("done")).map { r =>
      assertEquals(r, "done")
      assertEquals(counter.get(), 111)
    }
  }

  test("wrap extension should wrap ServiceAspect") {
    val events = ListBuffer.empty[String]

    val baseAspect = new ServiceAspect[IO, IO, String] {
      def visitUnaryToUnaryCall[Req, Res](
          callCtx: ServiceCallContext[Req, Res],
          req: Req,
          run: (Req, String) => IO[Res]
      ): IO[Res] = {
        events += "aspect-before"
        run(req, "context") <* IO(events += "aspect-after")
      }

      def visitUnaryToStreamingCall[Req, Res](
          callCtx: ServiceCallContext[Req, Res],
          req: Req,
          run: (Req, String) => Stream[IO, Res]
      ): Stream[IO, Res] =
        Stream.eval(IO(events += "aspect-stream")) >> run(req, "context")

      def visitStreamingToUnaryCall[Req, Res](
          callCtx: ServiceCallContext[Req, Res],
          req: Stream[IO, Req],
          run: (Stream[IO, Req], String) => IO[Res]
      ): IO[Res] = run(req, "context")

      def visitStreamingToStreamingCall[Req, Res](
          callCtx: ServiceCallContext[Req, Res],
          req: Stream[IO, Req],
          run: (Stream[IO, Req], String) => Stream[IO, Res]
      ): Stream[IO, Res] = run(req, "context")

      def visitUnaryToUnaryCallTrailers[Req, Res](
          callCtx: ServiceCallContext[Req, Res],
          req: Req,
          run: (Req, String) => IO[(Res, Metadata)]
      ): IO[(Res, Metadata)] = run(req, "context")

      def visitStreamingToUnaryCallTrailers[Req, Res](
          callCtx: ServiceCallContext[Req, Res],
          req: Stream[IO, Req],
          run: (Stream[IO, Req], String) => IO[(Res, Metadata)]
      ): IO[(Res, Metadata)] = run(req, "context")
    }

    val middleware = new ServiceAspectMiddleware[IO, String] {
      def unary[Req, Res](callCtx: ServiceCallContext[Req, Res], ctx: String): IO ~> IO =
        new (IO ~> IO) {
          def apply[A](fa: IO[A]): IO[A] =
            IO(events += s"middleware-before-$ctx") *> fa <* IO(events += s"middleware-after-$ctx")
        }

      def streaming[Req, Res](callCtx: ServiceCallContext[Req, Res], ctx: String): Stream[IO, *] ~> Stream[IO, *] =
        new (Stream[IO, *] ~> Stream[IO, *]) {
          def apply[A](s: Stream[IO, A]): Stream[IO, A] =
            Stream.eval(IO(events += s"middleware-stream-$ctx")) >> s
        }
    }

    val wrappedAspect = baseAspect.wrap(middleware)
    val callCtx = mockServiceCallContext()

    wrappedAspect
      .visitUnaryToUnaryCall(callCtx, "request", (_: Any, ctx: String) => IO.pure(s"response-$ctx"))
      .map { r =>
        assertEquals(r, "response-context")
        assertEquals(
          events.toList,
          List("aspect-before", "middleware-before-context", "middleware-after-context", "aspect-after")
        )
      }
  }

  private def mockServiceCallContext(): ServiceCallContext[Any, Any] =
    ServiceCallContext(
      methodDescriptor = MethodDescriptor
        .newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("test/method")
        .setRequestMarshaller(new NoopMarshaller)
        .setResponseMarshaller(new NoopMarshaller)
        .build(),
      metadata = new Metadata()
    )

  private class NoopMarshaller extends io.grpc.MethodDescriptor.Marshaller[Any] {
    def stream(value: Any): java.io.InputStream = null
    def parse(stream: java.io.InputStream): Any = null
  }
}
