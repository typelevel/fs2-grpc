package org.lyranthe.fs2_grpc
package java_runtime
package server

import cats.effect._
import cats.implicits._
import fs2._
import io.grpc._
import org.lyranthe.fs2_grpc.java_runtime.shared.Readiness

class Fs2ServerCallHandler[F[_]](val dummy: Boolean = false) extends AnyVal {

  def unaryToUnaryCall[Request, Response](
      implementation: (Request, Metadata) => F[Response],
      options: ServerCallOptions = ServerCallOptions.default
  )(implicit F: ConcurrentEffect[F]): ServerCallHandler[Request, Response] =
    new ServerCallHandler[Request, Response] {
      def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        val listener = Fs2UnaryServerCallListener[F](call, F.unit, options).unsafeRun()
        listener.unsafeUnaryResponse(headers, _ flatMap { request => implementation(request, headers) })
        listener
      }
    }

  def unaryToStreamingCall[Request, Response](
      implementation: (Request, Metadata) => Stream[F, Response],
      options: ServerCallOptions = ServerCallOptions.default
  )(implicit F: ConcurrentEffect[F]): ServerCallHandler[Request, Response] =
    new ServerCallHandler[Request, Response] {
      def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        Readiness[F].flatMap { readiness =>
          Fs2UnaryServerCallListener[F](call, readiness.signal, options).map { listener =>
            listener.unsafeStreamResponse(
              readiness,
              new Metadata(),
              v => Stream.eval(v) flatMap { request => implementation(request, headers) }
            )
            listener
          }
        }.unsafeRun()
      }
    }

  def streamingToUnaryCall[Request, Response](
      implementation: (Stream[F, Request], Metadata) => F[Response],
      options: ServerCallOptions = ServerCallOptions.default
  )(implicit F: ConcurrentEffect[F]): ServerCallHandler[Request, Response] =
    new ServerCallHandler[Request, Response] {
      def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        val listener = Fs2StreamServerCallListener[F](call, F.unit, options).unsafeRun()
        listener.unsafeUnaryResponse(headers, implementation(_, headers))
        listener
      }
    }

  def streamingToStreamingCall[Request, Response](
      implementation: (Stream[F, Request], Metadata) => Stream[F, Response],
      options: ServerCallOptions = ServerCallOptions.default
  )(implicit F: ConcurrentEffect[F]): ServerCallHandler[Request, Response] =
    new ServerCallHandler[Request, Response] {
      def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {

        Readiness[F].flatMap { readiness =>
          Fs2StreamServerCallListener[F](call, readiness.signal, options).map { listener=>
            listener.unsafeStreamResponse(readiness, headers, implementation(_, headers))
            listener
          }
        }.unsafeRun()
      }
    }
}

object Fs2ServerCallHandler {
  def apply[F[_]]: Fs2ServerCallHandler[F] = new Fs2ServerCallHandler[F]
}
