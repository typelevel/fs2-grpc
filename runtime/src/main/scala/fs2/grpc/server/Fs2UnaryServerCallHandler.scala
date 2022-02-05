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

import cats.effect.Async
import cats.effect.std.Dispatcher
import io.grpc._

object Fs2UnaryServerCallHandler {
  import Fs2StatefulServerCall.Cancel
  private val Noop: Cancel = () => ()
  private val Closed: Cancel = () => ()

  private def mkListener[Request, Response](
      run: Request => Cancel,
      call: ServerCall[Request, Response]
  ): ServerCall.Listener[Request] =
    new ServerCall.Listener[Request] {

      private[this] var request: Request = _
      private[this] var cancel: Cancel = Noop

      override def onCancel(): Unit = {
        cancel()
        ()
      }

      override def onMessage(message: Request): Unit =
        if (request == null) {
          request = message
        } else if (cancel eq Noop) {
          earlyClose(Status.INTERNAL.withDescription("Too many requests"))
        }

      override def onHalfClose(): Unit =
        if (cancel eq Noop) {
          if (request == null) {
            earlyClose(Status.INTERNAL.withDescription("Half-closed without a request"))
          } else {
            cancel = run(request)
          }
        }

      private def earlyClose(status: Status): Unit = {
        cancel = Closed
        call.close(status, new Metadata())
      }
    }

  def unary[F[_]: Async, Request, Response](
      impl: (Request, Metadata) => F[Response],
      options: ServerOptions,
      dispatcher: Dispatcher[F]
  ): ServerCallHandler[Request, Response] =
    new ServerCallHandler[Request, Response] {
      private val opt = options.callOptionsFn(ServerCallOptions.default)

      def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        val responder = Fs2StatefulServerCall.setup(opt, call, dispatcher)
        call.request(2)
        mkListener[Request, Response](req => responder.unary(impl(req, headers)), call)
      }
    }

  def stream[F[_]: Async, Request, Response](
      impl: (Request, Metadata) => fs2.Stream[F, Response],
      options: ServerOptions,
      dispatcher: Dispatcher[F]
  ): ServerCallHandler[Request, Response] =
    new ServerCallHandler[Request, Response] {
      private val opt = options.callOptionsFn(ServerCallOptions.default)

      def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        val responder = Fs2StatefulServerCall.setup(opt, call, dispatcher)
        call.request(2)
        mkListener[Request, Response](req => responder.stream(impl(req, headers)), call)
      }
    }
}
