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

package fs2
package grpc
package server

import cats.effect._
import cats.effect.std.Dispatcher
import cats.effect.testkit.TestContext
import cats.effect.testkit.TestControl
import io.grpc._
import scala.concurrent.duration._

class ServerSuite extends Fs2GrpcSuite {

  private val compressionOps =
    ServerOptions.default.configureCallOptions(_.withServerCompressor(Some(GzipCompressor)))

  private def startCall(
      implement: Fs2ServerCallHandler[IO] => ServerCallHandler[String, Int],
      serverOptions: ServerOptions = ServerOptions.default
  )(call: ServerCall[String, Int], thunk: ServerCall.Listener[String] => IO[Unit]): IO[Unit] =
    for {
      releaseRef <- IO.ref[IO[Unit]](IO.unit)
      startBarrier <- Deferred[IO, Unit]
      tc <- TestControl.execute {
        for {
          allocated <- Dispatcher[IO].map(Fs2ServerCallHandler[IO](_, serverOptions)).allocated
          (handler, release) = allocated
          _ <- releaseRef.set(release)
          listener <- IO(implement(handler).startCall(call, new Metadata()))
          _ <- startBarrier.get
          _ <- IO.defer(thunk(listener))
        } yield ()
      }
      _ <- tc.tick
      _ <- startBarrier.complete(())
      _ <- tc.tickAll
      _ <- releaseRef.get
    } yield ()

  private def syncCall(
      fs: (ServerCall.Listener[String] => Unit)*
  ): ServerCall.Listener[String] => IO[Unit] =
    listener => IO(fs.foreach(_.apply(listener)))

  test("unaryToUnary with compression") {
    testCompression(_.unaryToUnaryCall((req, _) => IO(req.length)))
  }

  test("unaryToStream with compression") {
    testCompression(_.unaryToStreamingCall((req, _) => Stream.emit(req.length).repeatN(5)))
  }

  test("streamToUnary with compression") {
    testCompression(_.streamingToUnaryCall((req, _) => req.compile.foldMonoid.map(_.length)))
  }

  test("streamToStream with compression")(
    testCompression(_.streamingToStreamingCall((req, _) => req.map(_.length)))
  )

  private def testCompression(sync: Fs2ServerCallHandler[IO] => ServerCallHandler[String, Int]): IO[Unit] = {
    val dummy = new DummyServerCall
    startCall(sync, compressionOps)(dummy, _ => IO.unit) >> IO {
      assertEquals(dummy.explicitCompressor, Some("gzip"))
    }
  }

  test("single message to unaryToUnary") {
    val dummy = new DummyServerCall
    startCall(_.unaryToUnaryCall((req, _) => IO(req.length)))(
      dummy,
      syncCall(_.onMessage("123"), _.onHalfClose())
    ) >> IO {
      assertEquals(dummy.explicitCompressor, None)
      assertEquals(dummy.messages.size, 1)
      assertEquals(dummy.messages(0), 3)
      assertEquals(dummy.currentStatus.isDefined, true)
      assertEquals(dummy.currentStatus.get.isOk, true)
    }
  }

  test("cancellation for unaryToUnary") {
    val dummy = new DummyServerCall
    startCall(_.unaryToUnaryCall((req, _) => IO(req.length)))(
      dummy,
      syncCall(_.onCancel())
    ) >> IO {
      assertEquals(dummy.currentStatus, None)
      assertEquals(dummy.messages.length, 0)
    }
  }

  runTest("cancellation on the fly for unaryToUnary") { (tc, d) =>
    val dummy = new DummyServerCall
    val listener = Fs2ServerCallHandler[IO](d, ServerOptions.default)
      .unaryToUnaryCall[String, Int]((req, _) => IO(req.length).delayBy(10.seconds))
      .startCall(dummy, new Metadata())

    listener.onMessage("123")
    listener.onHalfClose()
    tc.tick()
    listener.onCancel()
    tc.tick()

    assertEquals(dummy.currentStatus.map(_.getCode), Some(Status.Code.CANCELLED))
    assertEquals(dummy.messages.length, 0)
  }

  runTest("multiple messages to unaryToUnary")(multipleUnaryToUnary())
  runTest("multiple messages to unaryToUnary with compression")(multipleUnaryToUnary(compressionOps))

  private def multipleUnaryToUnary(
      options: ServerOptions = ServerOptions.default
  ): (TestContext, Dispatcher[IO]) => Unit = { (tc, d) =>
    val dummy = new DummyServerCall
    val listener = Fs2ServerCallHandler[IO](d, options)
      .unaryToUnaryCall[String, Int]((req, _) => IO(req.length))
      .startCall(dummy, new Metadata())

    listener.onMessage("123")
    listener.onMessage("456")
    listener.onHalfClose()
    tc.tick()

    assertEquals(dummy.currentStatus.map(_.getCode), Some(Status.Code.INTERNAL))
  }

  test("no messages to unaryToUnary") {
    val dummy = new DummyServerCall
    startCall(_.unaryToUnaryCall((req, _) => IO(req.length)))(
      dummy,
      syncCall(_.onHalfClose())
    ) >> IO {
      assertEquals(dummy.currentStatus.map(_.getCode), Some(Status.Code.INTERNAL))
    }
  }

  runTest0("resource awaits termination of server") { (tc, r, _) =>
    import fs2.grpc.syntax.all._
    import netty.shaded.io.grpc.netty.NettyServerBuilder

    val result = NettyServerBuilder.forPort(0).resource[IO].use(IO.pure).unsafeToFuture()(r)
    tc.tick()

    val server = result.value.get.get
    assert(server.isTerminated)
  }

  runTest("single message to unaryToStreaming")(singleUnaryToStreaming())
  runTest("single message to unaryToStreaming with compression")(singleUnaryToStreaming(compressionOps))

  private def singleUnaryToStreaming(
      options: ServerOptions = ServerOptions.default
  ): (TestContext, Dispatcher[IO]) => Unit = { (tc, d) =>
    val dummy = new DummyServerCall
    val listener = Fs2ServerCallHandler[IO](d, options)
      .unaryToStreamingCall[String, Int]((s, _) => Stream(s).map(_.length).repeat.take(5))
      .startCall(dummy, new Metadata())

    listener.onMessage("123")
    listener.onHalfClose()
    tc.tick()

    assertEquals(dummy.messages.size, 5)
    assertEquals(dummy.messages(0), 3)
    assertEquals(dummy.currentStatus.isDefined, true)
    assertEquals(dummy.currentStatus.get.isOk, true)
  }

  runTest("zero messages to streamingToStreaming") { (tc, d) =>
    val dummy = new DummyServerCall

    val handler = Fs2ServerCallHandler[IO](d, ServerOptions.default)
      .streamingToStreamingCall[String, Int]((_, _) => Stream.emit(3).repeat.take(5))
    val listener = handler.startCall(dummy, new Metadata())

    listener.onHalfClose()
    tc.tick()

    assertEquals(dummy.messages.size, 5)
    assertEquals(dummy.messages(0), 3)
    assertEquals(dummy.currentStatus.isDefined, true)
    assertEquals(dummy.currentStatus.get.isOk, true)
  }

  test("cancellation for streamingToStreaming") {
    val dummy = new DummyServerCall
    startCall(
      _.streamingToStreamingCall((_, _) => Stream.emit(3).repeat.take(5).zipLeft(Stream.awakeDelay[IO](1.seconds)))
    )(
      dummy,
      syncCall(_.onCancel())
    ) >> IO {
      assertEquals(dummy.currentStatus.map(_.getCode), Some(Status.Code.CANCELLED))
    }
  }

  runTest("messages to streamingToStreaming")(multipleStreamingToStreaming())
  runTest("messages to streamingToStreaming with compression")(multipleStreamingToStreaming(compressionOps))

  private def multipleStreamingToStreaming(
      options: ServerOptions = ServerOptions.default
  ): (TestContext, Dispatcher[IO]) => Unit = { (tc, d) =>
    val dummy = new DummyServerCall
    val handler = Fs2ServerCallHandler[IO](d, options)
      .streamingToStreamingCall[String, Int]((req, _) => req.map(_.length).intersperse(0))
    val listener = handler.startCall(dummy, new Metadata())

    listener.onMessage("a")
    listener.onMessage("ab")
    listener.onHalfClose()
    tc.tick()

    assertEquals(dummy.messages.length, 3)
    assertEquals(dummy.messages.toList, List(1, 0, 2))
    assertEquals(dummy.currentStatus.isDefined, true)
    assertEquals(dummy.currentStatus.get.isOk, true)
  }

  runTest("messages to streamingToStreaming with error") { (tc, d) =>
    val dummy = new DummyServerCall
    val error = new RuntimeException("hello")

    val handler = Fs2ServerCallHandler[IO](d, ServerOptions.default)
      .streamingToStreamingCall[String, Int]((req, _) =>
        req.map(_.length) ++ Stream.emit(0) ++ Stream.raiseError[IO](error)
      )
    val listener = handler.startCall(dummy, new Metadata())

    listener.onMessage("a")
    listener.onMessage("ab")
    listener.onHalfClose()
    tc.tick()

    assertEquals(dummy.messages.length, 3)
    assertEquals(dummy.messages.toList, List(1, 2, 0))
    assertEquals(dummy.currentStatus.isDefined, true)
    assertEquals(dummy.currentStatus.get.isOk, false)
  }

  runTest("streaming to unary")(streamingToUnary())
  runTest("streaming to unary with compression")(streamingToUnary(compressionOps))

  private def streamingToUnary(
      so: ServerOptions = ServerOptions.default
  ): (TestContext, Dispatcher[IO]) => Unit = { (tc, d) =>
    val implementation: Stream[IO, String] => IO[Int] =
      _.compile.foldMonoid.map(_.length)

    val dummy = new DummyServerCall

    val handler = Fs2ServerCallHandler[IO](d, so)
      .streamingToUnaryCall[String, Int]((req, _) => implementation(req))
    val listener = handler.startCall(dummy, new Metadata())

    listener.onMessage("ab")
    listener.onMessage("abc")
    listener.onHalfClose()
    tc.tick()

    assertEquals(dummy.messages.length, 1)
    assertEquals(dummy.messages(0), 5)
    assertEquals(dummy.currentStatus.isDefined, true)
    assertEquals(dummy.currentStatus.get.isOk, true)
  }

}
