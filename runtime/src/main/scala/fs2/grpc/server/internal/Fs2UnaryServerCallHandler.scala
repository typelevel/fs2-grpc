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

package fs2.grpc.server.internal

import cats.effect.Ref
import cats.effect.SyncIO
import fs2.grpc.server.ServerCallOptions
import fs2.grpc.server.ServerOptions
import io.grpc._

private[server] object Fs2UnaryServerCallHandler {

  import Fs2ServerCall.Cancel

  sealed trait CallerState[A]
  object CallerState {
    def init[A](cb: A => SyncIO[Cancel]): SyncIO[Ref[SyncIO, CallerState[A]]] =
      Ref[SyncIO].of[CallerState[A]](PendingMessage(cb))
  }
  case class PendingMessage[A](callback: A => SyncIO[Cancel]) extends CallerState[A] {
    def receive(a: A): PendingHalfClose[A] = PendingHalfClose(callback, a)
  }
  case class PendingHalfClose[A](callback: A => SyncIO[Cancel], received: A) extends CallerState[A] {
    def call(): SyncIO[Called[A]] = callback(received).map(Called.apply)
  }
  case class Called[A](cancel: Cancel) extends CallerState[A]
  case class Cancelled[A]() extends CallerState[A]

  private def mkListener[Request, Response](
      call: Fs2ServerCall[Request, Response],
      state: Ref[SyncIO, CallerState[Request]]
  ): ServerCall.Listener[Request] =
    new ServerCall.Listener[Request] {
      override def onCancel(): Unit =
        state.get
          .flatMap {
            case Called(cancel) => cancel >> state.set(Cancelled())
            case _ => SyncIO.unit
          }
          .unsafeRunSync()

      override def onMessage(message: Request): Unit =
        state.get
          .flatMap {
            case s: PendingMessage[Request] =>
              state.set(s.receive(message))
            case _: PendingHalfClose[Request] =>
              sendError(Status.INTERNAL.withDescription("Too many requests"))
            case _ =>
              SyncIO.unit
          }
          .unsafeRunSync()

      override def onHalfClose(): Unit =
        state.get
          .flatMap {
            case s: PendingHalfClose[Request] =>
              s.call().flatMap(state.set)
            case _: PendingMessage[Request] =>
              sendError(Status.INTERNAL.withDescription("Half-closed without a request"))
            case _ =>
              SyncIO.unit
          }
          .unsafeRunSync()

      private def sendError(status: Status): SyncIO[Unit] =
        state.set(Cancelled()) >> call.close(status, new Metadata())
    }

  def mkHandler[G[_], Request, Response](
      impl: (Request, Metadata) => G[Response],
      options: ServerOptions
  )(start: (Fs2ServerCall[Request, Response], G[Response]) => SyncIO[Cancel]): ServerCallHandler[Request, Response] =
    new ServerCallHandler[Request, Response] {
      private val opt = options.callOptionsFn(ServerCallOptions.default)

      def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        for {
          call <- Fs2ServerCall.setup(opt, call)
          // We expect only 1 request, but we ask for 2 requests here so that if a misbehaving client
          // sends more than 1 requests, ServerCall will catch it.
          _ <- call.request(2)
          state <- CallerState.init[Request](req => start(call, impl(req, headers)))
        } yield mkListener[Request, Response](call, state)
      }.unsafeRunSync()
    }
}
