package fs2.grpc.shared

import cats.effect.std.Dispatcher
import cats.effect.{Async, SyncIO}
import cats.syntax.all._
import fs2.Stream
import fs2.concurrent.SignallingRef
import io.grpc.{ClientCall, ServerCall}

private[grpc] trait StreamOutput[F[_], T] {
  def onReady: F[Unit]

  def onReadySync(dispatcher: Dispatcher[F]): SyncIO[Unit] = SyncIO.delay(dispatcher.unsafeRunSync(onReady))

  def writeStream(s: Stream[F, T]): Stream[F, Unit]
}

private [grpc] object StreamOutput {
  def client[F[_], Request, Response](c: ClientCall[Request, Response])
    (implicit F: Async[F]): F[StreamOutput[F, Request]] = {
    SignallingRef[F].of(0L).map { readyState =>
      new StreamOutputImpl[F, Request](
        readyState,
        isReady = F.delay(c.isReady),
        sendMessage = m => F.delay(c.sendMessage(m)))
    }
  }

  def server[F[_], Request, Response](c: ServerCall[Request, Response])
    (implicit F: Async[F]): F[StreamOutput[F, Response]] = {
    SignallingRef[F].of(0L).map { readyState =>
      new StreamOutputImpl[F, Response](
        readyState,
        isReady = F.delay(c.isReady),
        sendMessage = m => F.delay(c.sendMessage(m)))
    }
  }
}

private[grpc] class StreamOutputImpl[F[_], T](
  readyCountRef: SignallingRef[F, Long],
  isReady: F[Boolean],
  sendMessage: T => F[Unit],
)(implicit F: Async[F]) extends StreamOutput[F, T] {
  override def onReady: F[Unit] = readyCountRef.update(_ + 1L)

  override def writeStream(s: Stream[F, T]): Stream[F, Unit] = s.evalMap(sendWhenReady)

  private def sendWhenReady(msg: T): F[Unit] = {
    val send = sendMessage(msg)
    isReady.ifM(send, {
      readyCountRef.get.flatMap { readyState =>
        // If isReady is now true, don't wait (we may have missed the onReady signal)
        isReady.ifM(send, {
          // otherwise wait until readyState has been incremented
          readyCountRef.waitUntil(_ > readyState) *> send
        })
      }
    })
  }
}
