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
import cats.effect.Concurrent
import cats.effect.std.Queue

private[grpc] trait StreamIngest[F[_], T] {
  def onMessage(msg: T): F[Unit]
  def onClose(error: Option[Throwable]): F[Unit]
  def messages: Stream[F, T]
}

private[grpc] object StreamIngest {

  // Minimum value of `prefetchN` parameter to use
  // internal buffering of incoming messages in `StreamIngest`
  val BufferingThreshold = 14 // 12 + 4 + 14 * 8 = 128 bytes for compressed pointers

  sealed trait State[+T]
  final case class Done(opt: Option[Throwable]) extends State[Nothing]

  sealed trait Buffering[+T] extends State[T] {
    def requested: Int

    def append[T2 >: T](value: T2): (Option[Chunk[T2]], Buffering[T2])

    def requestForMore(): Option[(Int, Buffering[T])]
    def split(): Option[(Chunk[T], Buffering[T])]

    protected def receivedOne: Int = math.max(0, requested - 1)
  }
  final case class ChunkBuffer[+T](requested: Int, limit: Int, chunk: Chunk[T]) extends Buffering[T] {
    def append[T2 >: T](value: T2): (Option[Chunk[T2]], Buffering[T2]) = {
      val updChunk = chunk ++ Chunk.singleton(value)

      if (updChunk.size < limit) (none, copy(requested = receivedOne, chunk = updChunk))
      else (updChunk.toIndexedChunk.some, copy(requested = receivedOne, chunk = Chunk.empty))
    }

    def requestForMore(): Option[(Int, Buffering[T])] = {
      val additional = limit - requested
      if (additional <= 0) none
      else (additional, copy(requested = requested + additional)).some
    }

    def split(): Option[(Chunk[T], Buffering[T])] =
      if (chunk.isEmpty) none
      else (chunk.toIndexedChunk, copy(chunk = Chunk.empty[T])).some
  }
  final case class ArrayBuffer[+T](requested: Int, array: Array[AnyRef], offset: Int, count: Int) extends Buffering[T] {
    private def chunk(length: Int): Chunk[T] = Chunk.array(array, offset, length).asInstanceOf[Chunk[T]]

    def append[T2 >: T](value: T2): (Option[Chunk[T2]], Buffering[T2]) = {
      array(offset + count) = value.asInstanceOf[AnyRef]
      val updCount = count + 1

      if (offset + updCount < array.length) (none, copy(requested = receivedOne, count = updCount))
      else (chunk(updCount).some, copy(receivedOne, new Array[AnyRef](array.length), 0, 0))
    }

    def requestForMore(): Option[(Int, Buffering[T])] = {
      val additional = array.length - requested
      if (additional <= 0) none
      else (additional, copy(requested = requested + additional)).some
    }

    def split(): Option[(Chunk[T], Buffering[T])] =
      if (count == 0) none
      else (chunk(count), copy(offset = offset + count, count = 0)).some
  }

  object State {
    def apply[T](limit: Int): State[T] =
      if (limit < BufferingThreshold) ChunkBuffer(0, limit, Chunk.empty[T])
      else ArrayBuffer(0, new Array[AnyRef](limit), 0, 0)
  }

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
    private val limit: Int = math.max(1, prefetchN)

    def onMessage(msg: T): F[Unit] =
      queue.offer(msg.asRight)

    def onClose(error: Option[Throwable]): F[Unit] =
      queue.offer(error.asLeft)

    val messages: Stream[F, T] = {
      def loop(state: State[T]): F[Option[(Chunk[T], State[T])]] = state match {
        case Done(None) => F.pure(none)
        case Done(Some(err)) => F.raiseError(err)
        case buf: Buffering[T] =>
          def requestIfNeeded: F[Buffering[T]] = buf.requestForMore() match {
            case Some((adds, buf)) => request(adds).as(buf)
            case None => F.pure(buf)
          }

          def bufferOrEmit(buf: Buffering[T], value: T): F[Option[(Chunk[T], State[T])]] =
            buf.append(value) match {
              case (Some(chunk), buf) => F.pure((chunk, buf).some)
              case (None, buf) => loop(buf)
            }

          def waitOrEmit(buf: Buffering[T]): F[Option[(Chunk[T], State[T])]] = buf.split() match {
            case some: Some[_] => F.pure(some)
            case None =>
              queue.take.flatMap {
                case Right(value) => bufferOrEmit(buf, value)
                case Left(opt) => loop(Done(opt))
              }
          }

          queue.tryTake.flatMap {
            case None => requestIfNeeded >>= waitOrEmit
            case Some(Right(value)) => bufferOrEmit(buf, value)
            case Some(Left(err)) =>
              buf.split() match {
                case Some((chunk, _)) => F.pure((chunk, Done(err)).some)
                case None => loop(Done(err))
              }
          }
      }

      Stream.eval(F.pure(limit).map(State(_))).flatMap { z =>
        Stream.unfoldChunkEval[F, State[T], T](z)(loop)
      }
    }
  }

}
