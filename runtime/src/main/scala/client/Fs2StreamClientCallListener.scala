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

import cats.MonadThrow
import cats.implicits._
import cats.effect.kernel.Concurrent
import cats.effect.std.{Dispatcher, Queue}
import io.grpc.{ClientCall, Metadata, Status}

class Fs2StreamClientCallListener[F[_], Response] private (
    request: Int => F[Unit],
    queue: Queue[F, Either[GrpcStatus, Response]],
    dispatcher: Dispatcher[F]
)(implicit F: MonadThrow[F])
    extends ClientCall.Listener[Response] {

  override def onMessage(message: Response): Unit =
    dispatcher.unsafeRunSync(queue.offer(message.asRight))

  override def onClose(status: Status, trailers: Metadata): Unit =
    dispatcher.unsafeRunSync(queue.offer(GrpcStatus(status, trailers).asLeft))

  def stream: Stream[F, Response] = {

    val run: F[Option[Response]] =
      queue.take.flatMap {
        case Right(v) => v.some.pure[F] <* request(1)
        case Left(GrpcStatus(status, trailers)) =>
          if (!status.isOk) F.raiseError(status.asRuntimeException(trailers))
          else none[Response].pure[F]
      }

    Stream.repeatEval(run).unNoneTerminate
  }
}

object Fs2StreamClientCallListener {

  private[client] def apply[F[_]: Concurrent, Response](
      request: Int => F[Unit],
      dispatcher: Dispatcher[F]
  ): F[Fs2StreamClientCallListener[F, Response]] =
    Queue.unbounded[F, Either[GrpcStatus, Response]].map { q =>
      new Fs2StreamClientCallListener[F, Response](request, q, dispatcher)
    }
}
