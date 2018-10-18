package org.lyranthe.fs2_grpc.java_runtime.server

import cats.effect._
import cats.implicits._
import fs2._
import io.grpc._

class Fs2ServerCallHandler[F[_]](val dummy: Boolean = false) extends AnyVal {
  def unaryToUnaryCall[Request, Response](implementation: (Request, Metadata) => F[Response])(
      implicit F: ConcurrentEffect[F],
      cs: ContextShift[IO]): ServerCallHandler[Request, Response] =
    new ServerCallHandler[Request, Response] {
      def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        val listener = Fs2UnaryServerCallListener[F].unsafeCreate(call)
        listener.unsafeUnaryResponse(headers, _ flatMap { request =>
          implementation(request, headers)
        })
        listener
      }
    }

  def unaryToStreamingCall[Request, Response](implementation: (Request, Metadata) => Stream[F, Response])(
      implicit F: ConcurrentEffect[F],
      cs: ContextShift[IO]): ServerCallHandler[Request, Response] =
    new ServerCallHandler[Request, Response] {
      def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        val listener = Fs2UnaryServerCallListener[F].unsafeCreate(call)
        listener.unsafeStreamResponse(new Metadata(),
                                      v =>
                                        Stream.eval(v) flatMap { request =>
                                          implementation(request, headers)
                                      })
        listener
      }
    }

  def streamingToUnaryCall[Request, Response](implementation: (Stream[F, Request], Metadata) => F[Response])(
      implicit F: ConcurrentEffect[F],
      cs: ContextShift[IO]): ServerCallHandler[Request, Response] =
    new ServerCallHandler[Request, Response] {
      def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        val listener = Fs2StreamServerCallListener[F].unsafeCreate(call)
        listener.unsafeUnaryResponse(headers, implementation(_, headers))
        listener
      }
    }

  def streamingToStreamingCall[Request, Response](
      implementation: (Stream[F, Request], Metadata) => Stream[F, Response])(
      implicit F: ConcurrentEffect[F],
      cs: ContextShift[IO]): ServerCallHandler[Request, Response] =
    new ServerCallHandler[Request, Response] {
      def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        val listener = Fs2StreamServerCallListener[F].unsafeCreate(call)
        listener.unsafeStreamResponse(headers, implementation(_, headers))
        listener
      }
    }
}

object Fs2ServerCallHandler {
  def apply[F[_]]: Fs2ServerCallHandler[F] = new Fs2ServerCallHandler[F]
}
