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
import cats.effect.Concurrent
import cats.effect.std.Queue

private[grpc] trait StreamIngest[F[_], T] {
  def onMessage(msg: T): F[Unit]
  def onClose(error: Option[Throwable]): F[Unit]
  def messages: Stream[F, T]
}

private[grpc] object StreamIngest {

  def apply[F[_]: Concurrent, T](
      request: Int => F[Unit],
      prefetchN: Int
  ): F[StreamIngest[F, T]] =
    Queue
      .unbounded[F, Either[Option[Throwable], T]]
      .map(q => create[F, T](request, prefetchN, q))

  def create[F[_], T](
      request: Int => F[Unit],
      prefetchN: Int,
      queue: Queue[F, Either[Option[Throwable], T]]
  )(implicit F: Concurrent[F]): StreamIngest[F, T] = new StreamIngest[F, T] {

    val limit: Int =
      math.max(1, prefetchN)

    val ensureMessages: F[Unit] =
      queue.size.flatMap(qs => request(1).whenA(qs < limit))

    def onMessage(msg: T): F[Unit] =
      queue.offer(msg.asRight) *> ensureMessages

    def onClose(error: Option[Throwable]): F[Unit] =
      queue.offer(error.asLeft)

    val messages: Stream[F, T] = {

      val run: F[Option[T]] =
        queue.take.flatMap {
          case Right(v) => ensureMessages *> v.some.pure[F]
          case Left(Some(error)) => F.raiseError(error)
          case Left(None) => none[T].pure[F]
        }

      Stream.repeatEval(run).unNoneTerminate

    }

  }

}
