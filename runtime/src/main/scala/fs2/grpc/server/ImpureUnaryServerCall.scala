/*
 * Copyright (c) 2022 naoh / Fs2 Grpc Developers
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

package fs2.grpc.server

import cats.effect.Async
import cats.effect.Sync
import cats.effect.kernel.Outcome
import cats.effect.std.Dispatcher
import fs2.grpc.server.ImpureUnaryServerCall.Cancel
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import scala.concurrent.Future


object ImpureUnaryServerCall {
  type Cancel = () => Any
  private val Noop: Cancel = () => ()
  private val Closed: Cancel = () => ()

  private def tooManyRequests =
    Status.INTERNAL.withDescription("Too many requests").asRuntimeException

  def mkListener[Request, Response](
    run: Request => Cancel,
    call: ServerCall[Request, Response],
  ): ServerCall.Listener[Request] =
    new ServerCall.Listener[Request] {

      private var request: Request = _
      private var cancel: Cancel = Noop

      override def onCancel(): Unit =
        cancel()

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

  def unary[F[_] : Async, Request, Response](
    impl: (Request, Metadata) => F[Response],
    options: ServerOptions,
    dispatcher: Dispatcher[F],
  ): ServerCallHandler[Request, Response] =
    new ServerCallHandler[Request, Response] {
      private val opt = options.callOptionsFn(ServerCallOptions.default)

      def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        val responder = ImpureResponder.setup(opt, call, dispatcher)
        call.request(2)
        mkListener[Request, Response](
          req => responder.unary(impl(req, headers)), call)
      }
    }

  def stream[F[_] : Async, Request, Response](
    impl: (Request, Metadata) => fs2.Stream[F, Response],
    options: ServerOptions,
    dispatcher: Dispatcher[F],
  ): ServerCallHandler[Request, Response] =
    new ServerCallHandler[Request, Response] {
      private val opt = options.callOptionsFn(ServerCallOptions.default)

      def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        val responder = ImpureResponder.setup(opt, call, dispatcher)
        call.request(2)
        mkListener[Request, Response](
          req => responder.stream(impl(req, headers)), call)
      }
    }
}

final class ImpureResponder[F[_], Request, Response](
  val call: ServerCall[Request, Response],
  dispatcher: Dispatcher[F]
) {

  def stream(response: fs2.Stream[F, Response])(implicit F: Sync[F]): Cancel =
    dispatcher.unsafeRunCancelable(handleOutcome(response.map(sendMessage).compile.drain))

  def unary(response: F[Response])(implicit F: Sync[F]): Cancel =
    dispatcher.unsafeRunCancelable(handleOutcome(F.map(response)(sendMessage)))

  private var sentHeader: Boolean = false

  private def sendMessage(message: Response): Unit =
    if (!sentHeader) {
      sentHeader = true
      call.sendHeaders(new Metadata())
      call.sendMessage(message)
    } else {
      call.sendMessage(message)
    }

  private def handleOutcome[F[_]](completed: F[Unit])(implicit F: Sync[F]): F[Unit] = {
    F.guaranteeCase(completed) {
      case Outcome.Succeeded(_) => closeStream(Status.OK, new Metadata())
      case Outcome.Errored(e) =>
        e match {
          case ex: StatusException =>
            closeStream(ex.getStatus, Option(ex.getTrailers).getOrElse(new Metadata()))
          case ex: StatusRuntimeException =>
            closeStream(ex.getStatus, Option(ex.getTrailers).getOrElse(new Metadata()))
          case ex =>
            closeStream(Status.INTERNAL.withDescription(ex.getMessage).withCause(ex), new Metadata())
        }
      case Outcome.Canceled() => closeStream(Status.CANCELLED, new Metadata())
    }
  }

  private def closeStream[F[_]](status: Status, metadata: Metadata)(implicit F: Sync[F]): F[Unit] =
    F.delay(call.close(status, metadata))
}

object ImpureResponder {
  def setup[F[_], I, O](
    options: ServerCallOptions,
    call: ServerCall[I, O],
    dispatcher: Dispatcher[F]
  ): ImpureResponder[F, I, O] = {
    call.setMessageCompression(options.messageCompression)
    options.compressor.map(_.name).foreach(call.setCompression)
    new ImpureResponder[F, I, O](call, dispatcher)
  }
}