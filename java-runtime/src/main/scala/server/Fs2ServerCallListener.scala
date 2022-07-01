package org.lyranthe.fs2_grpc
package java_runtime
package server

import cats.effect._
import cats.effect.concurrent.Deferred
import cats.implicits._
import fs2.Stream
import io.grpc.{Metadata, Status, StatusException, StatusRuntimeException}
import org.lyranthe.fs2_grpc.java_runtime.shared.Readiness

private[server] trait Fs2ServerCallListener[F[_], G[_], Request, Response] {
  def source: G[Request]
  def isCancelled: Deferred[F, Unit]
  def call: Fs2ServerCall[F, Request, Response]

  private def reportError(t: Throwable)(implicit F: Sync[F]): F[Unit] = {
    t match {
      case ex: StatusException =>
        call.closeStream(ex.getStatus, Option(ex.getTrailers).getOrElse(new Metadata()))
      case ex: StatusRuntimeException =>
        call.closeStream(ex.getStatus, Option(ex.getTrailers).getOrElse(new Metadata()))
      case ex =>
        // TODO: Customize failure trailers?
        call.closeStream(Status.INTERNAL.withDescription(ex.getMessage).withCause(ex), new Metadata())
    }
  }

  private def handleUnaryResponse(headers: Metadata, response: F[Response])(implicit F: Sync[F]): F[Unit] =
    call.sendHeaders(headers) *> call.request(1) *> response >>= call.sendMessageImmediately

  private def handleStreamResponse(readiness: Readiness[F], headers: Metadata, response: Stream[F, Response])(implicit F: Sync[F]): F[Unit] =
    call.sendHeaders(headers) *> call.request(1) *> response.evalMap(call.sendMessageWhenReady(readiness)).compile.drain

  private def unsafeRun(f: F[Unit])(implicit F: ConcurrentEffect[F]): Unit = {
    val bracketed = F.guaranteeCase(f) {
      case ExitCase.Completed => call.closeStream(Status.OK, new Metadata())
      case ExitCase.Canceled => call.closeStream(Status.CANCELLED, new Metadata())
      case ExitCase.Error(t) => reportError(t)
    }

    // Exceptions are reported by closing the call
    F.runAsync(F.race(bracketed, isCancelled.get))(_ => IO.unit).unsafeRunSync()
  }

  def unsafeUnaryResponse(headers: Metadata, implementation: G[Request] => F[Response])(implicit
      F: ConcurrentEffect[F]
  ): Unit =
    unsafeRun(handleUnaryResponse(headers, implementation(source)))

  def unsafeStreamResponse(readiness: Readiness[F], headers: Metadata, implementation: G[Request] => Stream[F, Response])(implicit
      F: ConcurrentEffect[F]
  ): Unit =
    unsafeRun(handleStreamResponse(readiness, headers, implementation(source)))
}
