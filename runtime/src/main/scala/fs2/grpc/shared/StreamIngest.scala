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

import cats.implicits.*
import cats.effect.Async
import cats.effect.std.{Queue, unsafe}

private[grpc] trait StreamIngest[F[_], T] {
  def unsafeOnMessage(msg: T): Unit
  def unsafeOnClose(error: Option[Throwable]): Unit
  def messages: Stream[F, T]
}

private[grpc] object StreamIngest {

  def apply[F[_]: Async, T](
      request: Int => F[Unit],
      prefetchN: Int
  ): F[StreamIngest[F, T]] =
    Queue
      .unsafeUnbounded[F, Either[Option[Throwable], T]]
      .map(q => create[F, T](request, prefetchN, q))

  def create[F[_], T](
      request: Int => F[Unit],
      prefetchN: Int,
      queue: unsafe.UnboundedQueue[F, Either[Option[Throwable], T]]
  )(implicit F: Async[F]): StreamIngest[F, T] = new StreamIngest[F, T] {
    private val limit: Int = math.max(1, prefetchN)

    def unsafeOnMessage(msg: T): Unit =
      queue.unsafeOffer(msg.asRight)

    def unsafeOnClose(error: Option[Throwable]): Unit =
      queue.unsafeOffer(error.asLeft)

    val messages: Stream[F, T] = {
      type Requested = Int
      type S = Either[Option[Throwable], (Requested, Chunk[T])]
      def receivedOne(requested: Requested): Requested = math.max(0, requested - 1)
      def requestIfNeeded(requested: Requested): F[Requested] = {
        val additional = math.max(0, limit - requested)
        request(additional).whenA(additional > 0).as(requested + additional)
      }

      def zero(requested: Requested): S = (requested, Chunk.empty).asRight

      def loop(state: S): F[Option[(Chunk[T], S)]] =
        state match {
          case Left(None) => F.pure(none)
          case Left(Some(err)) => F.raiseError(err)
          case Right((requested, acc)) =>
            queue.tryTake.flatMap {
              case Some(Right(value)) => loop((receivedOne(requested), (acc ++ Chunk.singleton(value))).asRight)
              case Some(Left(err)) =>
                if (acc.isEmpty) loop(err.asLeft)
                else F.pure((acc.toIndexedChunk, err.asLeft).some)
              case None =>
                def await(requested: Requested) = if (acc.isEmpty) queue.take.flatMap {
                  case Right(value) =>
                    loop((receivedOne(requested), Chunk.singleton(value)).asRight)
                  case Left(err) => loop(err.asLeft)
                }
                else F.pure((acc.toIndexedChunk, zero(requested)).some)

                requestIfNeeded(requested) >>= await
            }
        }

      Stream.unfoldChunkEval(zero(0))(loop)
    }
  }

}
