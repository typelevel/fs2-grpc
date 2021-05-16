package org.lyranthe.fs2_grpc
package java_runtime
package client

import cats.syntax.all._
import cats.effect._
import cats.effect.concurrent.Ref
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
    (Ref.of[F, Int](prefetchN), InspectableQueue.unbounded[F, Either[GrpcStatus, T]])
      .mapN((d, q) => create[F, T](request, prefetchN, d, q)) <* request(prefetchN)

  def create[F[_], T](
      request: Int => F[Unit],
      prefetchN: Int,
      demand: Ref[F, Int],
      queue: InspectableQueue[F, Either[GrpcStatus, T]]
  )(implicit F: ConcurrentEffect[F]): StreamIngest[F, T] = new StreamIngest[F, T] {

    def onMessage(msg: T): F[Unit] =
      decreaseDemandBy(1) *> queue.enqueue1(msg.asRight)

    def onClose(status: GrpcStatus): F[Unit] =
      queue.enqueue1(status.asLeft)

    def ensureMessages(nextWhenEmpty: Int): F[Unit] = (demand.get, queue.getSize)
      .mapN((cd, qs) => fetch(nextWhenEmpty).whenA((cd + qs) < 1))
      .flatten

    def decreaseDemandBy(n: Int): F[Unit] =
      demand.update(d => math.max(d - n, 0))

    def increaseDemandBy(n: Int): F[Unit] =
      demand.update(_ + n)

    def fetch(n: Int): F[Unit] =
      request(n) *> increaseDemandBy(n)

    val messages: Stream[F, T] = {

      val run: F[Option[T]] =
        queue.dequeue1.flatMap {
          case Right(v) => v.some.pure[F] <* ensureMessages(prefetchN)
          case Left(GrpcStatus(status, trailers)) =>
            if (!status.isOk) F.raiseError(status.asRuntimeException(trailers))
            else none[T].pure[F]
        }

      Stream.repeatEval(run).unNoneTerminate
    }

  }

}
