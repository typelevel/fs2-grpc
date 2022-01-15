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
package server

import cats.MonadError
import cats.syntax.all._
import cats.effect.kernel.{Async, Deferred, Ref}
import cats.effect.std.Dispatcher
import io.grpc._

class Fs2UnaryServerCallListener[F[_], Request, Response] private (
    request: Ref[F, Option[Request]],
    isComplete: Deferred[F, Unit],
    val isCancelled: Deferred[F, Unit],
    val call: Fs2ServerCall[F, Request, Response],
    val dispatcher: Dispatcher[F]
)(implicit F: MonadError[F, Throwable])
    extends ServerCall.Listener[Request]
    with Fs2ServerCallListener[F, F, Request, Response] {

  import Fs2UnaryServerCallListener._

  override def onCancel(): Unit =
    dispatcher.unsafeRunSync(isCancelled.complete(()).void)

  override def onMessage(message: Request): Unit = {
    dispatcher.unsafeRunSync(
      request.access
        .flatMap[Unit] { case (curValue, modify) =>
          if (curValue.isDefined)
            F.raiseError(statusException(TooManyRequests))
          else
            modify(message.some).void
        }
    )
  }

  override def onHalfClose(): Unit =
    dispatcher.unsafeRunSync(isComplete.complete(()).void)

  override def source: F[Request] =
    for {
      _ <- isComplete.get
      valueOrNone <- request.get
      value <- valueOrNone.fold[F[Request]](F.raiseError(statusException(NoMessage)))(F.pure)
    } yield value
}

object Fs2UnaryServerCallListener {

  val TooManyRequests: String = "Too many requests"
  val NoMessage: String = "No message for unary call"

  private val statusException: String => StatusRuntimeException = msg =>
    new StatusRuntimeException(Status.INTERNAL.withDescription(msg))

  class PartialFs2UnaryServerCallListener[F[_]](val dummy: Boolean = false) extends AnyVal {

    private[server] def apply[Request, Response](
        call: ServerCall[Request, Response],
        dispatch: Dispatcher[F],
        options: ServerOptions
    )(implicit F: Async[F]): F[Fs2UnaryServerCallListener[F, Request, Response]] = for {
      request <- Ref.of[F, Option[Request]](none)
      isComplete <- Deferred[F, Unit]
      isCancelled <- Deferred[F, Unit]
      serverCall <- Fs2ServerCall[F, Request, Response](call, options)
    } yield new Fs2UnaryServerCallListener[F, Request, Response](request, isComplete, isCancelled, serverCall, dispatch)

  }

  private[server] def apply[F[_]] = new PartialFs2UnaryServerCallListener[F]
}
