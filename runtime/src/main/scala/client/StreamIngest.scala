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

import cats.implicits._
import cats.effect.kernel.{Concurrent, Ref}
import cats.effect.std.Queue

private[client] trait StreamIngest[F[_], T] {
  def onMessage(msg: T): F[Unit]
  def onClose(status: GrpcStatus): F[Unit]
  def messages: Stream[F, T]
}

private[client] object StreamIngest {

  def apply[F[_]: Concurrent, T](
      request: Int => F[Unit],
      prefetchN: Int
  ): F[StreamIngest[F, T]] =
    (Concurrent[F].ref(prefetchN), Queue.unbounded[F, Either[GrpcStatus, T]])
      .mapN((d, q) => create[F, T](request, prefetchN, d, q))

  def create[F[_], T](
      request: Int => F[Unit],
      prefetchN: Int,
      demand: Ref[F, Int],
      queue: Queue[F, Either[GrpcStatus, T]]
  )(implicit F: Concurrent[F]): StreamIngest[F, T] = new StreamIngest[F, T] {

    def onMessage(msg: T): F[Unit] =
      decreaseDemandBy(1) *> queue.offer(msg.asRight)

    def onClose(status: GrpcStatus): F[Unit] =
      queue.offer(status.asLeft)

    def ensureMessages(nextWhenEmpty: Int): F[Unit] =
      (demand.get, queue.size).mapN((cd, qs) => fetch(nextWhenEmpty).whenA((cd + qs) < 1)).flatten

    def decreaseDemandBy(n: Int): F[Unit] =
      demand.update(d => math.max(d - n, 0))

    def increaseDemandBy(n: Int): F[Unit] =
      demand.update(_ + n)

    def fetch(n: Int): F[Unit] =
      request(n) *> increaseDemandBy(n)

    val messages: Stream[F, T] = {

      val run: F[Option[T]] =
        queue.take.flatMap {
          case Right(v) => v.some.pure[F] <* ensureMessages(prefetchN)
          case Left(GrpcStatus(status, trailers)) =>
            if (!status.isOk) F.raiseError(status.asRuntimeException(trailers))
            else none[T].pure[F]
        }

      Stream.repeatEval(run).unNoneTerminate

    }

  }

}
