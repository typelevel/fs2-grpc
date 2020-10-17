package org.lyranthe.fs2_grpc
package java_runtime
package client

import scala.concurrent.duration._
import scala.concurrent.TimeoutException
import scala.util.Success
import cats.effect._
import fs2._
import io.grpc._

class ClientSuite extends Fs2GrpcSuite {

  private def fs2ClientCall(dummy: DummyClientCall, ur: UnsafeRunner[IO]) =
    new Fs2ClientCall[IO, String, Int](dummy, ur, _ => None)

  runTest0("single message to unaryToUnary") { (ec, r, ur) =>
    val dummy = new DummyClientCall()
    val client = fs2ClientCall(dummy, ur)
    val result = client.unaryToUnaryCall("hello", new Metadata()).unsafeToFuture()(r)

    ec.tick()
    dummy.listener.get.onMessage(5)

    // Check that call does not complete after result returns
    assertEquals(result.value, None)

    dummy.listener.get.onClose(Status.OK, new Metadata())

    // Check that call completes after status
    ec.tick()
    assertEquals(result.value, Some(Success(5)))
    assertEquals(dummy.messagesSent.size, 1)
    assertEquals(dummy.requested, 1)

  }

  runTest0("cancellation for unaryToUnary") { (ec, r, ur) =>
    val dummy = new DummyClientCall()
    val client = fs2ClientCall(dummy, ur)
    val result = client.unaryToUnaryCall("hello", new Metadata()).timeout(1.second).unsafeToFuture()(r)

    ec.tick()
    dummy.listener.get.onMessage(5)

    // Check that call does not complete after result returns
    ec.tick()
    assertEquals(result.value, None)

    // Check that call is cancelled after 1 second
    ec.tick(2.seconds)

    assert(result.value.get.isFailure)
    assert(result.value.get.failed.get.isInstanceOf[TimeoutException])
    assertEquals(dummy.cancelled.isDefined, true)

  }

  runTest0("no response message to unaryToUnary") { (ec, r, ur) =>
    val dummy = new DummyClientCall()
    val client = fs2ClientCall(dummy, ur)
    val result = client.unaryToUnaryCall("hello", new Metadata()).unsafeToFuture()(r)

    ec.tick()
    dummy.listener.get.onClose(Status.OK, new Metadata())

    // Check that call completes after status but no message
    ec.tick()
    assert(result.value.isDefined)
    assert(result.value.get.isFailure)
    assert(result.value.get.failed.get.isInstanceOf[StatusRuntimeException])
    assertEquals(dummy.messagesSent.size, 1)
    assertEquals(dummy.requested, 1)

  }

  runTest0("error response to unaryToUnary") { (ec, r, ur) =>
    val dummy = new DummyClientCall()
    val client = fs2ClientCall(dummy, ur)
    val result = client.unaryToUnaryCall("hello", new Metadata()).unsafeToFuture()(r)

    ec.tick()
    dummy.listener.get.onMessage(5)
    dummy.listener.get.onClose(Status.INTERNAL, new Metadata())

    // Check that call completes after status but no message
    ec.tick()
    assert(result.value.isDefined)
    assert(result.value.get.isFailure)
    assert(result.value.get.failed.get.isInstanceOf[StatusRuntimeException])
    assertEquals(
      result.value.get.failed.get
        .asInstanceOf[StatusRuntimeException]
        .getStatus,
      Status.INTERNAL
    )
    assertEquals(dummy.messagesSent.size, 1)
    assertEquals(dummy.requested, 1)

  }

  runTest0("stream to streamingToUnary") { (ec, r, ur) =>
    val dummy = new DummyClientCall()
    val client = fs2ClientCall(dummy, ur)
    val result = client
      .streamingToUnaryCall(Stream.emits(List("a", "b", "c")), new Metadata())
      .unsafeToFuture()(r)

    ec.tick()
    dummy.listener.get.onMessage(5)

    // Check that call does not complete after result returns
    ec.tick()
    assertEquals(result.value, None)

    dummy.listener.get.onClose(Status.OK, new Metadata())

    // Check that call completes after status
    ec.tick()
    assertEquals(result.value, Some(Success(5)))
    assertEquals(dummy.messagesSent.size, 3)
    assertEquals(dummy.requested, 1)

  }

  runTest0("0-length to streamingToUnary") { (ec, r, ur) =>
    val dummy = new DummyClientCall()
    val client = fs2ClientCall(dummy, ur)
    val result = client
      .streamingToUnaryCall(Stream.empty, new Metadata())
      .unsafeToFuture()(r)

    ec.tick()
    dummy.listener.get.onMessage(5)

    // Check that call does not complete after result returns
    ec.tick()
    assertEquals(result.value, None)

    dummy.listener.get.onClose(Status.OK, new Metadata())

    // Check that call completes after status
    ec.tick()
    assertEquals(result.value, Some(Success(5)))
    assertEquals(dummy.messagesSent.size, 0)
    assertEquals(dummy.requested, 1)

  }

  runTest0("single message to unaryToStreaming") { (ec, r, ur) =>
    val dummy = new DummyClientCall()
    val client = fs2ClientCall(dummy, ur)
    val result = client.unaryToStreamingCall("hello", new Metadata()).compile.toList.unsafeToFuture()(r)

    ec.tick()
    dummy.listener.get.onMessage(1)
    dummy.listener.get.onMessage(2)
    dummy.listener.get.onMessage(3)

    // Check that call does not complete after result returns
    ec.tick()
    assertEquals(result.value, None)

    dummy.listener.get.onClose(Status.OK, new Metadata())

    // Check that call completes after status
    ec.tick()
    assertEquals(result.value, Some(Success(List(1, 2, 3))))
    assertEquals(dummy.messagesSent.size, 1)
    assertEquals(dummy.requested, 4)

  }

  runTest0("stream to streamingToStreaming") { (ec, r, ur) =>
    val dummy = new DummyClientCall()
    val client = fs2ClientCall(dummy, ur)
    val result =
      client
        .streamingToStreamingCall(Stream.emits(List("a", "b", "c", "d", "e")), new Metadata())
        .compile
        .toList
        .unsafeToFuture()(r)

    ec.tick()
    dummy.listener.get.onMessage(1)
    dummy.listener.get.onMessage(2)
    dummy.listener.get.onMessage(3)

    // Check that call does not complete after result returns
    ec.tick()
    assertEquals(result.value, None)

    dummy.listener.get.onClose(Status.OK, new Metadata())

    // Check that call completes after status
    ec.tick()
    assertEquals(result.value, Some(Success(List(1, 2, 3))))
    assertEquals(dummy.messagesSent.size, 5)
    assertEquals(dummy.requested, 4)

  }

  runTest0("cancellation for streamingToStreaming") { (ec, r, ur) =>
    val dummy = new DummyClientCall()
    val client = fs2ClientCall(dummy, ur)
    val result =
      client
        .streamingToStreamingCall(Stream.emits(List("a", "b", "c", "d", "e")), new Metadata())
        .compile
        .toList
        .timeout(1.second)
        .unsafeToFuture()(r)

    ec.tick()
    dummy.listener.get.onMessage(1)
    dummy.listener.get.onMessage(2)
    dummy.listener.get.onMessage(3)

    // Check that call does not complete after result returns
    ec.tick()
    assertEquals(result.value, None)

    // Check that call completes after status
    ec.tick(2.seconds)
    assert(result.value.get.isFailure)
    assert(result.value.get.failed.get.isInstanceOf[TimeoutException])
    assertEquals(dummy.cancelled.isDefined, true)

  }

  runTest0("error returned from streamingToStreaming") { (ec, r, ur) =>
    val dummy = new DummyClientCall()
    val client = fs2ClientCall(dummy, ur)
    val result =
      client
        .streamingToStreamingCall(Stream.emits(List("a", "b", "c", "d", "e")), new Metadata())
        .compile
        .toList
        .unsafeToFuture()(r)

    ec.tick()
    dummy.listener.get.onMessage(1)
    dummy.listener.get.onMessage(2)
    dummy.listener.get.onMessage(3)

    // Check that call does not complete after result returns
    ec.tick()
    assertEquals(result.value, None)

    dummy.listener.get.onClose(Status.INTERNAL, new Metadata())

    // Check that call completes after status
    ec.tick()
    assert(result.value.isDefined)
    assert(result.value.get.isFailure)
    assert(result.value.get.failed.get.isInstanceOf[StatusRuntimeException])
    assertEquals(
      result.value.get.failed.get
        .asInstanceOf[StatusRuntimeException]
        .getStatus,
      Status.INTERNAL
    )
    assertEquals(dummy.messagesSent.size, 5)
    assertEquals(dummy.requested, 4)

  }

  runTest0("resource awaits termination of managed channel") { (ec, r, _) =>
    import org.lyranthe.fs2_grpc.java_runtime.implicits._
    val result = ManagedChannelBuilder.forAddress("127.0.0.1", 0).resource[IO].use(IO.pure).unsafeToFuture()(r)

    ec.tick()

    val channel = result.value.get.get
    assert(channel.isTerminated)

  }

  runTest0("error adapter is used when applicable") { (ec, r, ur) =>
    def testCalls(shouldAdapt: Boolean, ur: UnsafeRunner[IO]): Unit = {

      def testAdapter(call: Fs2ClientCall[IO, String, Int] => IO[Unit]): Unit = {

        val (status, errorMsg) =
          if (shouldAdapt) (Status.ABORTED, "OhNoes!")
          else {
            (Status.INVALID_ARGUMENT, Status.INVALID_ARGUMENT.asRuntimeException().getMessage)
          }

        val adapter: StatusRuntimeException => Option[Exception] = _.getStatus match {
          case Status.ABORTED if shouldAdapt => Some(new RuntimeException(errorMsg))
          case _ => None
        }

        val dummy = new DummyClientCall()
        val client = new Fs2ClientCall[IO, String, Int](dummy, ur, adapter)
        val result = call(client).unsafeToFuture()(r)

        ec.tick()
        dummy.listener.get.onClose(status, new Metadata())
        ec.tick()

        assertEquals(result.value.get.failed.get.getMessage, errorMsg)
        ec.tickAll()
      }

      testAdapter(_.unaryToUnaryCall("hello", new Metadata()).void)
      testAdapter(_.unaryToStreamingCall("hello", new Metadata()).compile.toList.void)
      testAdapter(_.streamingToUnaryCall(Stream.emit("hello"), new Metadata()).void)
      testAdapter(_.streamingToStreamingCall(Stream.emit("hello"), new Metadata()).compile.toList.void)
    }

    ///

    testCalls(shouldAdapt = true, ur)
    testCalls(shouldAdapt = false, ur)

  }

}
