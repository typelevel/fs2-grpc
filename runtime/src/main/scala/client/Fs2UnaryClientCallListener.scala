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

import cats.MonadError
import cats.syntax.all._
import cats.effect._
import cats.effect.std.Dispatcher
import io.grpc._

class Fs2UnaryClientCallListener[F[_], Response] private (
    grpcStatus: Deferred[F, GrpcStatus],
    value: Ref[F, Option[Response]],
    dispatcher: Dispatcher[F]
)(implicit F: MonadError[F, Throwable])
    extends ClientCall.Listener[Response] {

  override def onClose(status: Status, trailers: Metadata): Unit =
    dispatcher.unsafeRunSync(grpcStatus.complete(GrpcStatus(status, trailers)).void)

  override def onMessage(message: Response): Unit =
    dispatcher.unsafeRunSync(value.set(message.some))

  def getValue: F[Response] = {
    for {
      r <- grpcStatus.get
      v <- value.get
      result <- {
        if (!r.status.isOk)
          F.raiseError(r.status.asRuntimeException(r.trailers))
        else {
          v match {
            case None =>
              F.raiseError(
                Status.INTERNAL
                  .withDescription("No value received for unary call")
                  .asRuntimeException(r.trailers)
              )
            case Some(v1) =>
              F.pure(v1)
          }
        }
      }
    } yield result
  }
}

object Fs2UnaryClientCallListener {

  private[client] def create[F[_]: Async, Response](
      dispatcher: Dispatcher[F]
  ): F[Fs2UnaryClientCallListener[F, Response]] =
    (Deferred[F, GrpcStatus], Ref.of[F, Option[Response]](none)).mapN((response, value) =>
      new Fs2UnaryClientCallListener[F, Response](response, value, dispatcher)
    )
}
