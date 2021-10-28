package org.lyranthe.fs2_grpc
package java_runtime
package client

import cats.syntax.all._
import cats.effect._
import fs2.Stream
import fs2.concurrent.InspectableQueue

private[client] trait StreamIngest[F[_], T] {
  def onMessage(msg: T): F[Unit]
  def onClose(status: GrpcStatus): F[Unit]
  def messages: Stream[F, T]
}

private[client] object StreamIngest {

  def apply[F[_]: ConcurrentEffect, T](
      request: Int => F[Unit],
      prefetchN: Int
  ): F[StreamIngest[F, T]] =
    InspectableQueue
      .unbounded[F, Either[GrpcStatus, T]]
      .map(q => create[F, T](request, prefetchN, q))

  def create[F[_], T](
      request: Int => F[Unit],
      prefetchN: Int,
      queue: InspectableQueue[F, Either[GrpcStatus, T]]
  )(implicit F: ConcurrentEffect[F]): StreamIngest[F, T] = new StreamIngest[F, T] {

    val limit: Int =
      math.max(1, prefetchN)

    val ensureMessages: F[Unit] =
      queue.getSize.flatMap(qs => request(1).whenA(qs < limit))

    def onMessage(msg: T): F[Unit] =
      queue.enqueue1(msg.asRight) *> ensureMessages

    def onClose(status: GrpcStatus): F[Unit] =
      queue.enqueue1(status.asLeft)

    val messages: Stream[F, T] = {

      val run: F[Option[T]] =
        queue.dequeue1.flatMap {
          case Right(v) => ensureMessages *> v.some.pure[F]
          case Left(GrpcStatus(status, trailers)) =>
            if (!status.isOk) F.raiseError(status.asRuntimeException(trailers))
            else none[T].pure[F]
        }

      Stream.repeatEval(run).unNoneTerminate
    }

  }

}
