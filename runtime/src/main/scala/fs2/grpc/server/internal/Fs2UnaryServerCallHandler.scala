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

import cats.effect.std.Dispatcher
import cats.effect.{Async, Ref, Sync, SyncIO}
import fs2.grpc.server.{ServerCallOptions, ServerOptions}
import fs2.grpc.shared.StreamOutput
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
      signalReadiness: SyncIO[Unit],
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

      override def onReady(): Unit = signalReadiness.unsafeRunSync()

      private def sendError(status: Status): SyncIO[Unit] =
        state.set(Cancelled()) >> call.close(status, new Metadata())
    }

  def unary[F[_]: Sync, Request, Response](
      impl: (Request, Metadata) => F[(Response, Metadata)],
      options: ServerOptions,
      dispatcher: Dispatcher[F]
  ): ServerCallHandler[Request, Response] =
    new ServerCallHandler[Request, Response] {
      private val opt = options.callOptionsFn(ServerCallOptions.default)

      def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] =
        startCallSync(call, SyncIO.unit, opt)(call => req => call.unary(impl(req, headers), dispatcher)).unsafeRunSync()
    }

  def stream[F[_], Request, Response](
      impl: (Request, Metadata) => fs2.Stream[F, Response],
      options: ServerOptions,
      dispatcher: Dispatcher[F]
  )(implicit F: Async[F]): ServerCallHandler[Request, Response] =
    new ServerCallHandler[Request, Response] {
      private val opt = options.callOptionsFn(ServerCallOptions.default)

      def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        val outputStream = dispatcher.unsafeRunSync(StreamOutput.server(call))
        startCallSync(call, outputStream.onReadySync(dispatcher), opt)(call =>
          req => {
            call.stream(outputStream.writeStream, impl(req, headers), dispatcher)
          }
        ).unsafeRunSync()
      }
    }

  private def startCallSync[F[_], Request, Response](
      call: ServerCall[Request, Response],
      signalReadiness: SyncIO[Unit],
      options: ServerCallOptions
  )(f: Fs2ServerCall[Request, Response] => Request => SyncIO[Cancel]): SyncIO[ServerCall.Listener[Request]] = {
    for {
      call <- Fs2ServerCall.setup(options, call)
      // We expect only 1 request, but we ask for 2 requests here so that if a misbehaving client
      // sends more than 1 requests, ServerCall will catch it.
      _ <- call.request(2)
      state <- CallerState.init(f(call))
    } yield mkListener[Request, Response](call, signalReadiness, state)
  }
}
