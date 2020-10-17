package org.lyranthe.fs2_grpc
package java_runtime
package server

import cats.effect._
import cats.effect.std.Dispatcher
import cats.implicits._
import fs2._
import io.grpc._

class Fs2ServerCallHandler[F[_]: Async] private (runner: UnsafeRunner[F]) {

  def unaryToUnaryCall[Request, Response](
      implementation: (Request, Metadata) => F[Response],
      options: ServerCallOptions = ServerCallOptions.default
  ): ServerCallHandler[Request, Response] = new ServerCallHandler[Request, Response] {
    def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
      val listener = runner.unsafeRunSync(Fs2UnaryServerCallListener[F](call, runner, options))
      listener.unsafeUnaryResponse(headers, _ flatMap { request => implementation(request, headers) })
      listener
    }
  }

  def unaryToStreamingCall[Request, Response](
      implementation: (Request, Metadata) => Stream[F, Response],
      options: ServerCallOptions = ServerCallOptions.default
  ): ServerCallHandler[Request, Response] = new ServerCallHandler[Request, Response] {
    def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
      val listener = runner.unsafeRunSync(Fs2UnaryServerCallListener[F](call, runner, options))
      listener.unsafeStreamResponse(
        new Metadata(),
        v => Stream.eval(v) flatMap { request => implementation(request, headers) }
      )
      listener
    }
  }

  def streamingToUnaryCall[Request, Response](
      implementation: (Stream[F, Request], Metadata) => F[Response],
      options: ServerCallOptions = ServerCallOptions.default
  ): ServerCallHandler[Request, Response] = new ServerCallHandler[Request, Response] {
    def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
      val listener = runner.unsafeRunSync(Fs2StreamServerCallListener[F](call, runner, options))
      listener.unsafeUnaryResponse(headers, implementation(_, headers))
      listener
    }
  }

  def streamingToStreamingCall[Request, Response](
      implementation: (Stream[F, Request], Metadata) => Stream[F, Response],
      options: ServerCallOptions = ServerCallOptions.default
  ): ServerCallHandler[Request, Response] = new ServerCallHandler[Request, Response] {
    def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
      val listener = runner.unsafeRunSync(Fs2StreamServerCallListener[F](call, runner, options))
      listener.unsafeStreamResponse(headers, implementation(_, headers))
      listener
    }
  }
}

object Fs2ServerCallHandler {

  def apply[F[_]: Async](dispatcher: Dispatcher[F]): Fs2ServerCallHandler[F] =
    Fs2ServerCallHandler[F](UnsafeRunner[F](dispatcher))

  private[server] def apply[F[_]: Async](runner: UnsafeRunner[F]): Fs2ServerCallHandler[F] =
    new Fs2ServerCallHandler[F](runner)
}
