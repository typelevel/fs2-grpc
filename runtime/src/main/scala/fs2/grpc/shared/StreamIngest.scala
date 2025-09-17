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
package shared

import cats.implicits._
import cats.effect.{Concurrent, Ref}
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
    (Ref[F].of(0), Queue.unbounded[F, Either[Option[Throwable], T]])
      .mapN((r, q) => create[F, T](request, prefetchN, r, q))

  def create[F[_], T](
      request: Int => F[Unit],
      prefetchN: Int,
      requested: Ref[F, Int],
      queue: Queue[F, Either[Option[Throwable], T]]
  )(implicit F: Concurrent[F]): StreamIngest[F, T] = new StreamIngest[F, T] {
    private val limit: Int = math.max(1, prefetchN)
    private def updateRequests: F[Unit] = {
      queue.size.flatMap { queued =>
        requested.flatModify { requested =>
          val total = queued + requested
          val additional = math.max(0, limit - total)

          (
            requested + additional,
            request(additional).whenA(additional > 0)
          )
        }
      }
    }

    def onMessage(msg: T): F[Unit] =
      queue.offer(msg.asRight) *> requested.update(r => math.max(0, r - 1))

    def onClose(error: Option[Throwable]): F[Unit] =
      queue.offer(error.asLeft)

    val messages: Stream[F, T] = {
      type S = Either[Option[Throwable], Chunk[T]]

      def zero: S = Chunk.empty.asRight
      def loop(state: S): F[Option[(Chunk[T], S)]] =
        state match {
          case Left(None) => F.pure(none)
          case Left(Some(err)) => F.raiseError(err)
          case Right(acc) =>
            queue.tryTake.flatMap {
              case Some(Right(value)) => loop((acc ++ Chunk.singleton(value)).asRight)
              case Some(Left(err)) =>
                if (acc.isEmpty) loop(err.asLeft)
                else F.pure((acc.toIndexedChunk, err.asLeft).some)
              case None =>
                val await = if (acc.isEmpty) queue.take.flatMap {
                  case Right(value) => loop(Chunk.singleton(value).asRight)
                  case Left(err) => loop(err.asLeft)
                }
                else F.pure((acc.toIndexedChunk, zero).some)

                updateRequests *> await
            }
        }

      Stream.unfoldChunkEval(zero)(loop)
    }
  }

}
