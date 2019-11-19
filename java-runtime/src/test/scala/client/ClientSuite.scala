package org.lyranthe.fs2_grpc
package java_runtime
package client

import cats.implicits._
import cats.effect.{ContextShift, IO, Timer}
import cats.effect.laws.util.TestContext
import fs2._
import io.grpc._
import minitest._
import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.Success

object ClientSuite extends SimpleTestSuite {

  def fs2ClientCall(dummy: DummyClientCall) =
    new Fs2ClientCall[IO, String, Int](dummy, _ => None)

  test("single message to unaryToUnary") {

    implicit val ec: TestContext      = TestContext()
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)

    val dummy  = new DummyClientCall()
    val client = fs2ClientCall(dummy)
    val result = client.unaryToUnaryCall("hello", new Metadata()).unsafeToFuture()
    dummy.listener.get.onMessage(5)

    // Check that call does not complete after result returns
    ec.tick()
    assertEquals(result.value, None)

    dummy.listener.get.onClose(Status.OK, new Metadata())

    // Check that call completes after status
    ec.tick()
    assertEquals(result.value, Some(Success(5)))
    assertEquals(dummy.messagesSent.size, 1)
    assertEquals(dummy.requested, 1)
  }

  test("cancellation for unaryToUnary") {

    implicit val ec: TestContext      = TestContext()
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)
    implicit val timer: Timer[IO]     = ec.timer

    val dummy  = new DummyClientCall()
    val client = fs2ClientCall(dummy)
    val result = client.unaryToUnaryCall("hello", new Metadata()).timeout(1.second).unsafeToFuture()

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

  test("no response message to unaryToUnary") {

    implicit val ec: TestContext      = TestContext()
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)

    val dummy  = new DummyClientCall()
    val client = fs2ClientCall(dummy)
    val result = client.unaryToUnaryCall("hello", new Metadata()).unsafeToFuture()

    dummy.listener.get.onClose(Status.OK, new Metadata())

    // Check that call completes after status but no message
    ec.tick()
    assert(result.value.isDefined)
    assert(result.value.get.isFailure)
    assert(result.value.get.failed.get.isInstanceOf[StatusRuntimeException])
    assertEquals(dummy.messagesSent.size, 1)
    assertEquals(dummy.requested, 1)
  }

  test("error response to unaryToUnary") {

    implicit val ec: TestContext      = TestContext()
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)


    val dummy  = new DummyClientCall()
    val client = fs2ClientCall(dummy)
    val result = client.unaryToUnaryCall("hello", new Metadata()).unsafeToFuture()
    dummy.listener.get.onMessage(5)

    dummy.listener.get.onClose(Status.INTERNAL, new Metadata())

    // Check that call completes after status but no message
    ec.tick()
    assert(result.value.isDefined)
    assert(result.value.get.isFailure)
    assert(result.value.get.failed.get.isInstanceOf[StatusRuntimeException])
    assertEquals(result.value.get.failed.get
                   .asInstanceOf[StatusRuntimeException]
                   .getStatus,
                 Status.INTERNAL)
    assertEquals(dummy.messagesSent.size, 1)
    assertEquals(dummy.requested, 1)
  }

  test("stream to streamingToUnary") {

    implicit val ec: TestContext      = TestContext()
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)


    val dummy  = new DummyClientCall()
    val client = fs2ClientCall(dummy)
    val result = client
      .streamingToUnaryCall(Stream.emits(List("a", "b", "c")), new Metadata())
      .unsafeToFuture()

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

  test("0-length to streamingToUnary") {

    implicit val ec: TestContext      = TestContext()
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)


    val dummy  = new DummyClientCall()
    val client = fs2ClientCall(dummy)
    val result = client
      .streamingToUnaryCall(Stream.empty, new Metadata())
      .unsafeToFuture()

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

  test("single message to unaryToStreaming") {

    implicit val ec: TestContext      = TestContext()
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)

    val dummy  = new DummyClientCall()
    val client = fs2ClientCall(dummy)
    val result = client.unaryToStreamingCall("hello", new Metadata()).compile.toList.unsafeToFuture()

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

  test("stream to streamingToStreaming") {

    implicit val ec: TestContext      = TestContext()
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)

    val dummy  = new DummyClientCall()
    val client = fs2ClientCall(dummy)
    val result =
      client
        .streamingToStreamingCall(Stream.emits(List("a", "b", "c", "d", "e")), new Metadata())
        .compile
        .toList
        .unsafeToFuture()
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

  test("cancellation for streamingToStreaming") {

    implicit val ec: TestContext      = TestContext()
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)
    implicit val timer: Timer[IO]     = ec.timer

    val dummy  = new DummyClientCall()
    val client = fs2ClientCall(dummy)
    val result =
      client
        .streamingToStreamingCall(Stream.emits(List("a", "b", "c", "d", "e")), new Metadata())
        .compile
        .toList
        .timeout(1.second)
        .unsafeToFuture()
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

  test("error returned from streamingToStreaming") {

    implicit val ec: TestContext      = TestContext()
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)

    val dummy  = new DummyClientCall()
    val client = fs2ClientCall(dummy)
    val result =
      client
        .streamingToStreamingCall(Stream.emits(List("a", "b", "c", "d", "e")), new Metadata())
        .compile
        .toList
        .unsafeToFuture()

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
    assertEquals(result.value.get.failed.get
                   .asInstanceOf[StatusRuntimeException]
                   .getStatus,
                 Status.INTERNAL)
    assertEquals(dummy.messagesSent.size, 5)
    assertEquals(dummy.requested, 4)
  }

  test("resource awaits termination of managed channel") {
    implicit val ec: TestContext  = TestContext()

    import implicits._
    val result = ManagedChannelBuilder.forAddress("127.0.0.1", 0).resource[IO].use(IO.pure).unsafeToFuture()

    ec.tick()

    val channel = result.value.get.get
    assert(channel.isTerminated)
  }

  test("error adapter is used when applicable") {

    implicit val ec: TestContext      = TestContext()
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)

    def testCalls(shouldAdapt: Boolean): Unit = {

      def testAdapter(call: Fs2ClientCall[IO, String, Int] => IO[Unit]): Unit = {

        val (status, errorMsg) = if(shouldAdapt) (Status.ABORTED, "OhNoes!") else {
          (Status.INVALID_ARGUMENT, Status.INVALID_ARGUMENT.asRuntimeException().getMessage)
        }

        val adapter: StatusRuntimeException => Option[Exception] = _.getStatus match {
          case Status.ABORTED if shouldAdapt => Some(new RuntimeException(errorMsg))
          case _                             => None
        }

        val dummy  = new DummyClientCall()
        val client = new Fs2ClientCall[IO, String, Int](dummy, adapter)
        val result = call(client).unsafeToFuture()

        dummy.listener.get.onClose(status, new Metadata())
        ec.tick()

        assertEquals(result.value.get.failed.get.getMessage, errorMsg)
      }

      testAdapter(_.unaryToUnaryCall("hello", new Metadata()).void)
      testAdapter(_.unaryToStreamingCall("hello", new Metadata()).compile.toList.void)
      testAdapter(_.streamingToUnaryCall(Stream.emit("hello"), new Metadata()).void)
      testAdapter(_.streamingToStreamingCall(Stream.emit("hello"), new Metadata()).compile.toList.void)
    }

    ///

    testCalls(shouldAdapt = true)
    testCalls(shouldAdapt = false)

  }

}
