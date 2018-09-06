package org.lyranthe.fs2_grpc.java_runtime.client

import cats.arrow.FunctionK
import cats.effect.{Concurrent, ContextShift, IO, LiftIO}
import cats.implicits._
import fs2.concurrent.Queue
import fs2.{Pull, RaiseThrowable, Stream}
import io.grpc.{ClientCall, Metadata, Status}

class Fs2StreamClientCallListener[Response](request: Int => Unit, queue: Queue[IO, Either[GrpcStatus, Response]])
    extends ClientCall.Listener[Response] {
  override def onMessage(message: Response): Unit = {
    request(1)
    queue.enqueue1(message.asRight).unsafeRunSync()
  }

  override def onClose(status: Status, trailers: Metadata): Unit = {
    queue.enqueue1(GrpcStatus(status, trailers).asLeft).unsafeRunSync()
  }

  def stream[F[_]: RaiseThrowable](implicit F: LiftIO[F]): Stream[F, Response] = {
    def go(q: Stream[F, Either[GrpcStatus, Response]]): Pull[F, Response, Unit] = {
      // TODO: Write in terms of Segment
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

    go(queue.dequeue.translate(FunctionK.lift(F.liftIO _))).stream
  }
}

object Fs2StreamClientCallListener {
  def apply[F[_], Response](request: Int => Unit)(implicit F: Concurrent[F],
                                                  cs: ContextShift[IO]): F[Fs2StreamClientCallListener[Response]] = {
    F.liftIO(
      Queue
        .unbounded[IO, Either[GrpcStatus, Response]]
        .map(new Fs2StreamClientCallListener[Response](request, _)))
  }
}
