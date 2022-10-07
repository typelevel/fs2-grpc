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
package client

import scala.concurrent.duration._
import scala.concurrent.TimeoutException
import cats.syntax.all._
import cats.effect._
import cats.effect.std.Dispatcher
import cats.effect.testkit.TestControl
import fs2.concurrent.SignallingRef
import io.grpc._
import munit._

class ClientSuite2 extends CatsEffectSuite {

  runTest("single message to unaryToUnary") { (dummy, client) =>
    val prog = IO.deferred[Int].flatMap { r =>
      val action = client
        .unaryToUnaryCall("hello", new Metadata())
        .flatTap(r.complete)

      for {
        f <- action.start
        _ <- IO.sleep(5.millis)
        // Check that call does not complete after result returns
        _ <- assertIO(r.tryGet, None)
        l <- IO(dummy.listener).map(expectSome)
        _ <- IO(l.onMessage(5))
        _ <- IO.sleep(5.millis)
        _ <- assertIO(r.tryGet, None)
        _ <- IO(l.onClose(Status.OK, new Metadata()))
        // Check that call completes after status
        fr <- f.join
        v <- r.tryGet
      } yield {
        assert(fr.isSuccess)
        assertEquals(v, Some(5))
        assertEquals(dummy.messagesSent.size, 1)
        assertEquals(dummy.requested, 2)
      }
    }

    TestControl.executeEmbed(prog)
  }

  runTest("cancellation for unaryToUnary") { (dummy, client) =>
    val prog = IO.deferred[Int].flatMap { r =>
      val action = client
        .unaryToUnaryCall("hello", new Metadata())
        .timeout(1.second)
        .flatTap(r.complete)

      for {
        f <- action.start
        _ <- IO.sleep(5.millis)
        // Check that call does not complete after result returns
        l <- IO(dummy.listener).map(expectSome)
        _ <- IO(l.onMessage(5))
        _ <- IO.sleep(5.millis)
        _ <- assertIO(r.tryGet, None)
        // Check that call is cancelled after 1 second
        _ <- IO.sleep(1.second)
        // Check that call completes after status
        fr <- f.join
        _ <- assertIO(r.tryGet, None)
      } yield {
        assert(fr.fold(false, _.isInstanceOf[TimeoutException], _ => false))
        assert(dummy.cancelled.isDefined)
      }
    }

    TestControl.executeEmbed(prog)
  }

  runTest("no response message to unaryToUnary") { (dummy, client) =>
    val prog = {
      val action = client
        .unaryToUnaryCall("hello", new Metadata())

      for {
        f <- action.start
        _ <- IO.sleep(5.millis)
        l <- IO(dummy.listener).map(expectSome)
        _ <- IO(l.onClose(Status.OK, new Metadata()))
        // Check that call completes after status but no message
        fr <- f.join
      } yield {
        assert(fr.fold(false, _.isInstanceOf[StatusRuntimeException], _ => false))
        assertEquals(dummy.messagesSent.size, 1)
        assertEquals(dummy.requested, 2)
      }
    }

    TestControl.executeEmbed(prog)
  }

  runTest("error response to unaryToUnary") { (dummy, client) =>
    val prog = {
      val action = client
        .unaryToUnaryCall("hello", new Metadata())

      for {
        f <- action.start
        _ <- IO.sleep(5.millis)
        l <- IO(expectSome(dummy.listener))
        _ <- IO(l.onClose(Status.INTERNAL, new Metadata()))
        // Check that call completes after status but no message
        fr <- f.join
      } yield {
        assert(
          fr.fold(
            false,
            {
              case s: StatusRuntimeException if s.getStatus == Status.INTERNAL => true
              case _ => false
            },
            _ => false
          )
        )
        assertEquals(dummy.messagesSent.size, 1)
        assertEquals(dummy.requested, 2)
      }
    }

    TestControl.executeEmbed(prog)
  }

  runTest("stream to streamingToUnary") { (dummy, client) =>
    val prog = IO.deferred[Int].flatMap { r =>
      val action = client
        .streamingToUnaryCall(Stream.emits(List("a", "b", "c")), new Metadata())
        .flatTap(r.complete)

      for {
        f <- action.start
        _ <- IO.sleep(5.millis)
        l <- IO(dummy.listener).map(expectSome)
        _ <- IO(l.onMessage(5))
        // Check that call does not complete after result returns
        _ <- assertIO(r.tryGet, None)
        _ <- IO(l.onClose(Status.OK, new Metadata()))
        // Check that call completes after status
        _ <- f.join
        v <- r.tryGet
      } yield {
        assertEquals(v, Some(5))
        assertEquals(dummy.messagesSent.size, 3)
        assertEquals(dummy.requested, 2)
      }
    }

    TestControl.executeEmbed(prog)
  }

  runTest("stream to streamingToUnary - send respects readiness") { (dummy, client) =>
    val prog = IO.deferred[Int].flatMap { r =>
      val requests = Stream
        .emits(List("a", "b", "c", "d", "e"))
        .chunkLimit(1)
        .unchunks
        .evalTap(v => IO(dummy.setIsReady(false)).whenA(v == "c"))

      val action = client
        .streamingToUnaryCall(requests, new Metadata())
        .flatTap(r.complete)

      for {
        f <- action.start
        _ <- IO.sleep(5.millis)
        // Check that client has not sent messages when isReady == false
        _ <- IO(assertEquals(dummy.messagesSent.size, 2))
        _ <- assertIO(r.tryGet, None)
        _ <- IO(dummy.setIsReady(true))
        _ <- IO.sleep(5.millis)
        _ <- f.cancel
      } yield {
        // Check that client sends remaining messages after channel is ready
        assertEquals(dummy.messagesSent.size, 5)
      }
    }

    TestControl.executeEmbed(prog)
  }

  runTest("0-length to streamingToUnary") { (dummy, client) =>
    val prog = IO.deferred[Int].flatMap { r =>
      val action = client
        .streamingToUnaryCall(Stream.empty, new Metadata())
        .flatTap(r.complete)

      for {
        f <- action.start
        _ <- IO.sleep(5.millis)
        l <- IO(dummy.listener).map(expectSome)
        _ <- IO(l.onMessage(5))
        _ <- IO.sleep(5.millis)
        // Check that call does not complete after result returns
        _ <- assertIO(r.tryGet, None)
        _ <- IO(l.onClose(Status.OK, new Metadata()))
        // Check that call completes after status
        _ <- f.join
        v <- r.tryGet
      } yield {
        assertEquals(v, Some(5))
        assertEquals(dummy.messagesSent.size, 0)
        assertEquals(dummy.requested, 2)
      }
    }

    TestControl.executeEmbed(prog)
  }

  runTest("single message to unaryToStreaming") { (dummy, client) =>
    val setup = (IO.ref(0), SignallingRef[IO, Boolean](false))
    val prog = setup.flatMapN { case (r, s) =>
      val stream = client
        .unaryToStreamingCall("hello", new Metadata())
        .evalTap(i => r.update(_ + i))
        .interruptWhen(s)
        .compile
        .drain

      for {
        f <- stream.start
        _ <- IO.sleep(5.millis)
        l <- IO(dummy.listener).map(expectSome)
        _ <- IO(l.onMessage(1))
        _ <- IO(l.onMessage(2))
        _ <- IO(l.onMessage(3))
        _ <- IO.sleep(5.millis)
        _ <- IO(l.onClose(Status.OK, new Metadata()))
        _ <- IO.sleep(5.millis)
        _ <- IO(l.onMessage(4))
        _ <- s.set(true)
        _ <- f.join
        v <- r.get
      } yield {
        assertEquals(dummy.messagesSent.size, 1)
        assertEquals(dummy.requested, 2)
        assertEquals(v, 6)
      }
    }

    TestControl.executeEmbed(prog)
  }

  runTest("single message to unaryToStreaming - back pressure") { (dummy, client) =>
    val prog = IO.ref(0).flatMap { r =>
      val stream = client
        .unaryToStreamingCall("hello", new Metadata())
        .evalTap(i => r.update(_ + i))
        .take(2)
        .compile
        .drain

      for {
        f <- stream.start
        _ <- IO.sleep(5.millis)
        l <- IO(dummy.listener).map(expectSome)
        _ <- IO(l.onMessage(4))
        _ <- IO(l.onMessage(4))
        _ <- IO(l.onMessage(4))
        _ <- IO(l.onMessage(4))
        _ <- IO.sleep(5.millis)
        _ <- IO(l.onClose(Status.OK, new Metadata()))
        _ <- f.join
        v <- r.get
      } yield {
        assertEquals(dummy.messagesSent.size, 1)
        assertEquals(dummy.requested, 1)
        assertEquals(v, 8)
      }
    }

    TestControl.executeEmbed(prog)
  }

  runTest("stream to streamingToStreaming") { (dummy, client) =>
    val prog = Deferred[IO, List[Int]].flatMap { r =>
      val requests = Stream.emits(List("a", "b", "c", "d", "e"))
      val action = client
        .streamingToStreamingCall(requests, new Metadata())
        .compile
        .toList
        .flatTap(r.complete)

      for {
        f <- action.start
        _ <- IO.sleep(5.millis)
        l <- IO(dummy.listener).map(expectSome)
        _ <- IO(l.onMessage(1))
        _ <- IO(l.onMessage(2))
        _ <- IO(l.onMessage(3))
        _ <- IO.sleep(5.millis)
        // Check that call does not complete after result returns
        _ <- assertIO(r.tryGet, None)
        _ <- IO(l.onClose(Status.OK, new Metadata()))
        _ <- IO.sleep(5.millis)
        // Check that call completes after status
        _ <- f.join
        v <- r.tryGet
      } yield {
        assertEquals(v, Some(List(1, 2, 3)))
        assertEquals(dummy.messagesSent.size, 5)
        assertEquals(dummy.requested, 2)
      }
    }

    TestControl.executeEmbed(prog)
  }

  runTest("stream to streamingToStreaming - send respects readiness") { (dummy, client) =>
    val prog = Deferred[IO, List[Int]].flatMap { r =>
      val requests = Stream
        .emits(List("a", "b", "c", "d", "e"))
        .chunkLimit(1)
        .unchunks
        .evalTap(v => IO(dummy.setIsReady(false)).whenA(v == "c"))

      val action = client
        .streamingToStreamingCall(requests, new Metadata())
        .compile
        .toList
        .flatTap(r.complete)

      for {
        f <- action.start
        _ <- IO.sleep(5.millis)
        // Check that client has not sent messages when isReady == false
        _ <- IO(assertEquals(dummy.messagesSent.size, 2))
        _ <- assertIO(r.tryGet, None)
        l <- IO(dummy.listener).map(expectSome)
        _ <- IO(dummy.setIsReady(true))
        _ <- IO.sleep(5.millis)
        _ <- IO(l.onMessage(1))
        _ <- IO(l.onClose(Status.OK, new Metadata()))
        _ <- IO.sleep(5.millis)
        v <- r.tryGet
        _ <- f.join
      } yield {
        // Check that client sends remaining messages after channel is ready
        assertEquals(dummy.messagesSent.size, 5)
        assertEquals(v, Some(List(1)))
      }
    }

    TestControl.executeEmbed(prog)
  }

  runTest("cancellation for streamingToStreaming") { (dummy, client) =>
    val prog = Deferred[IO, List[Int]].flatMap { r =>
      val action = client
        .streamingToStreamingCall(Stream.emits(List("a", "b", "c", "d", "e")), new Metadata())
        .compile
        .toList
        .timeout(1.second)
        .flatTap(r.complete)

      for {
        f <- action.start
        _ <- IO.sleep(5.millis)
        l <- IO(dummy.listener).map(expectSome)
        _ <- IO(l.onMessage(1))
        _ <- IO(l.onMessage(2))
        _ <- IO(l.onMessage(3))
        _ <- IO.sleep(5.millis)
        // Check that call does not complete after result returns
        _ <- assertIO(r.tryGet, None)
        // Check that call completes after status
        _ <- IO.sleep(2.second)
        fr <- f.join
        _ <- assertIO(r.tryGet, None)
      } yield {
        assert(fr.isError)
        assert(fr.fold(false, _.isInstanceOf[TimeoutException], _ => false))
        assert(dummy.cancelled.isDefined)
      }
    }

    TestControl.executeEmbed(prog)
  }

  runTest("error returned from streamingToStreaming") { (dummy, client) =>
    val prog = Deferred[IO, List[Int]].flatMap { r =>
      val action = client
        .streamingToStreamingCall(Stream.emits(List("a", "b", "c", "d", "e")), new Metadata())
        .compile
        .toList
        .flatTap(r.complete)

      for {
        f <- action.start
        _ <- IO.sleep(5.millis)
        l <- IO(dummy.listener).map(expectSome)
        _ <- IO(l.onMessage(1))
        _ <- IO(l.onMessage(2))
        _ <- IO(l.onMessage(3))
        _ <- IO.sleep(5.millis)
        // Check that call does not complete after result returns
        _ <- assertIO(r.tryGet, None)
        // Check that call completes after status
        _ <- IO(l.onClose(Status.INTERNAL, new Metadata()))
        fr <- f.join
        _ <- assertIO(r.tryGet, None)
      } yield {
        assert(fr.isError)
        assert(
          fr.fold(
            false,
            {
              case e: StatusRuntimeException if e.getStatus() == Status.INTERNAL => true
              case _ => false
            },
            _ => false
          )
        )
        assertEquals(dummy.messagesSent.size, 5)
        assertEquals(dummy.requested, 2)
      }
    }

    TestControl.executeEmbed(prog)
  }

  test("resource awaits termination of managed channel") {
    import fs2.grpc.syntax.all._
    import netty.shaded.io.grpc.netty.NettyChannelBuilder

    val action = NettyChannelBuilder
      .forAddress("127.0.0.1", 0)
      .resource[IO]
      .use(IO.pure)
      .map(mc => assert(mc.isTerminated()))

    TestControl.executeEmbed(action)
  }

  test("error adapter is used when applicable") {

    def testCalls(shouldAdapt: Boolean): Unit = {

      def testAdapter(call: Fs2ClientCall[IO, String, Int] => IO[Unit]): Unit = {

        val (status, errMsg) =
          if (shouldAdapt)
            (Status.ABORTED, "OhNoes!")
          else
            (Status.INVALID_ARGUMENT, Status.INVALID_ARGUMENT.asRuntimeException().getMessage)

        val adapter: PartialFunction[StatusRuntimeException, Exception] = {
          case e: StatusRuntimeException if e.getStatus == Status.ABORTED && shouldAdapt => new RuntimeException(errMsg)
        }

        withDispatcher { d =>
          val dummy = new DummyClientCall()
          val options = ClientOptions.default.withErrorAdapter(adapter)
          val client = new Fs2ClientCall[IO, String, Int](dummy, d, options)

          val prog = for {
            f <- call(client).start
            _ <- IO.sleep(5.millis)
            l <- IO(dummy.listener).map(expectSome)
            _ <- IO(l.onClose(status, new Metadata()))
            _ <- IO.sleep(5.millis)
            fr <- f.join
          } yield {
            assert(fr.isError)
            assert(fr.fold(false, _.getMessage() == errMsg, _ => false))
          }
          TestControl.executeEmbed(prog)
        }
      }

      testAdapter(_.unaryToUnaryCall("hello", new Metadata()).void)
      testAdapter(_.unaryToStreamingCall("hello", new Metadata()).compile.toList.void)
      testAdapter(_.streamingToUnaryCall(Stream.emit("hello"), new Metadata()).void)
      testAdapter(_.streamingToStreamingCall(Stream.emit("hello"), new Metadata()).compile.toList.void)
    }

    testCalls(shouldAdapt = true)
    testCalls(shouldAdapt = false)
  }

  ///

  def fs2ClientCall(dummy: DummyClientCall, d: Dispatcher[IO]) =
    new Fs2ClientCall[IO, String, Int](dummy, d, ClientOptions.default)

  def withDispatcher[A](f: Dispatcher[IO] => IO[A]): Unit =
    Dispatcher.parallel[IO](true).use(f).void.unsafeRunSync()

  def runTest[A](
      name: String
  )(f: (DummyClientCall, Fs2ClientCall[IO, String, Int]) => IO[A])(implicit loc: Location): Unit =
    test(name)(withDispatcher[A] { d =>
      val dc = new DummyClientCall()
      val cc = fs2ClientCall(dc, d)
      f(dc, cc)
    })

  def expectSome[A](opt: Option[A]): A = opt match {
    case Some(l) => l
    case None => fail("Expected some")
  }

}
