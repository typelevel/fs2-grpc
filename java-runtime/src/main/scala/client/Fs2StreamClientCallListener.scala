package org.lyranthe.fs2_grpc
package java_runtime
package client

import cats.ApplicativeError
import cats.effect.kernel.Concurrent
import cats.effect.std.Dispatcher
import cats.implicits._
import fs2.{Pull, Stream}
import fs2.concurrent.Queue
import io.grpc.{ClientCall, Metadata, Status}

class Fs2StreamClientCallListener[F[_], Response] private (
    request: Int => Unit,
    queue: Queue[F, Either[GrpcStatus, Response]],
    dispatcher: Dispatcher[F]
)(implicit F: ApplicativeError[F, Throwable])
    extends ClientCall.Listener[Response] {

  override def onMessage(message: Response): Unit = {
    request(1)
    dispatcher.unsafeRunSync(queue.enqueue1(message.asRight))
  }

  override def onClose(status: Status, trailers: Metadata): Unit =
    dispatcher.unsafeRunSync(queue.enqueue1(GrpcStatus(status, trailers).asLeft))

  def stream: Stream[F, Response] = {

    def go(q: Stream[F, Either[GrpcStatus, Response]]): Pull[F, Response, Unit] = {
      q.pull.uncons1.flatMap {
        case Some((Right(v), tl)) => Pull.output1(v) >> go(tl)
        case Some((Left(GrpcStatus(status, trailers)), _)) =>
          if (!status.isOk)
            Pull.raiseError[F](status.asRuntimeException(trailers))
          else
            Pull.done
        case None => Pull.done
      }
    }

    go(queue.dequeue).stream
  }
}

object Fs2StreamClientCallListener {

  def apply[F[_]: Concurrent, Response](
      request: Int => Unit,
      dispatcher: Dispatcher[F]
  ): F[Fs2StreamClientCallListener[F, Response]] =
    Queue.unbounded[F, Either[GrpcStatus, Response]].map { q =>
      new Fs2StreamClientCallListener[F, Response](request, q, dispatcher)
    }
}
