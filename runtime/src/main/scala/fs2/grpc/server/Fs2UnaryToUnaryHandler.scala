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

import cats.syntax.all._
import cats.effect._
import cats.effect.std.Dispatcher
import io.grpc._

private[server] object Fs2UnaryToUnaryHandler {

  private def tooManyRequests =
    Status.INTERNAL.withDescription("Too many requests").asRuntimeException

  def mkListener[F[_], I, O](
      call: Fs2ServerCall[F, I, O],
      headers: Metadata,
      impl: (I, Metadata) => F[O],
      isCancelled: Deferred[F, Unit],
      canInvoke: Ref[F, Boolean],
      dispatcher: Dispatcher[F]
  )(implicit F: Async[F]): ServerCall.Listener[I] = new ServerCall.Listener[I] {

    val checkHandled: F[Unit] =
      canInvoke.get >>= { cond =>
        F.raiseError(tooManyRequests).unlessA(cond)
      }

    val setHandled: F[Unit] =
      canInvoke.set(false)

    override def onCancel(): Unit = {
      val action = isCancelled.complete(()).void
      dispatcher.unsafeRunAndForget(action)
    }

    override def onMessage(message: I): Unit = {
      val run = call.sendHeaders(headers) *> impl(message, headers) >>= call.sendMessage
      val action = F.race(call.handleOutcome(checkHandled *> run *> setHandled), isCancelled.get)
      dispatcher.unsafeRunAndForget(action)
    }

  }

  def apply[F[_]: Async, I, O](
      impl: (I, Metadata) => F[O],
      dispatcher: Dispatcher[F],
      options: ServerOptions
  ): ServerCallHandler[I, O] = new ServerCallHandler[I, O] {

    def startCall(call: ServerCall[I, O], headers: Metadata): ServerCall.Listener[I] = {

      val action: SyncIO[ServerCall.Listener[I]] = for {
        fs2Call <- Fs2ServerCall.make[F, I, O](call, options)
        isCancelled <- Deferred.in[SyncIO, F, Unit]
        canInvoke <- Ref.in[SyncIO, F, Boolean](true)
        listener = mkListener(fs2Call, headers, impl, isCancelled, canInvoke, dispatcher)
        _ <- SyncIO(fs2Call.call.request(1))
      } yield listener

      action.unsafeRunSync()

    }

  }

}
