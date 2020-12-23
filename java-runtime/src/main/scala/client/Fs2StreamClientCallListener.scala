package org.lyranthe.fs2_grpc
package java_runtime
package client

import cats.MonadError
import cats.implicits._
import cats.effect.kernel.Concurrent
import cats.effect.std.{Dispatcher, Queue}
import fs2.Stream
import io.grpc.{ClientCall, Metadata, Status}

class Fs2StreamClientCallListener[F[_], Response] private (
    request: Int => Unit,
    queue: Queue[F, Either[GrpcStatus, Response]],
    dispatcher: Dispatcher[F]
)(implicit F: MonadError[F, Throwable])
    extends ClientCall.Listener[Response] {

  override def onMessage(message: Response): Unit = {
    request(1)
    dispatcher.unsafeRunSync(queue.offer(message.asRight))
  }

  override def onClose(status: Status, trailers: Metadata): Unit =
    dispatcher.unsafeRunSync(queue.offer(GrpcStatus(status, trailers).asLeft))

  def stream: Stream[F, Response] = {

    val run: F[Option[Response]] =
      queue.take.flatMap {
        case Right(v) => v.some.pure[F]
        case Left(GrpcStatus(status, trailers)) =>
          if (!status.isOk) F.raiseError(status.asRuntimeException(trailers))
          else none[Response].pure[F]
      }

    Stream.repeatEval(run).unNoneTerminate
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
