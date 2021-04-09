/*
 * Copyright (c) 2018 Gary Coady
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

package org.lyranthe.fs2_grpc
package java_runtime
package server

import cats.effect._
import cats.effect.kernel.Deferred
import cats.effect.std.Dispatcher
import cats.implicits._
import fs2.Stream
import io.grpc.{Metadata, Status, StatusException, StatusRuntimeException}

private[server] trait Fs2ServerCallListener[F[_], G[_], Request, Response] {

  def source: G[Request]
  def isCancelled: Deferred[F, Unit]
  def call: Fs2ServerCall[F, Request, Response]
  def dispatcher: Dispatcher[F]

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
    call.sendHeaders(headers) *> call.request(1) *> response >>= call.sendMessage

  private def handleStreamResponse(headers: Metadata, response: Stream[F, Response])(implicit F: Sync[F]): F[Unit] =
    call.sendHeaders(headers) *> call.request(1) *> response.evalMap(call.sendMessage).compile.drain

  private def unsafeRun(f: F[Unit])(implicit F: Async[F]): Unit = {
    val bracketed = F.guaranteeCase(f) {
      case Outcome.Succeeded(_) => call.closeStream(Status.OK, new Metadata())
      case Outcome.Canceled() => call.closeStream(Status.CANCELLED, new Metadata())
      case Outcome.Errored(t) => reportError(t)
    }

    // Exceptions are reported by closing the call
    dispatcher.unsafeRunAndForget(F.race(bracketed, isCancelled.get))
  }

  def unsafeUnaryResponse(headers: Metadata, implementation: G[Request] => F[Response])(implicit
      F: Async[F]
  ): Unit =
    unsafeRun(handleUnaryResponse(headers, implementation(source)))

  def unsafeStreamResponse(headers: Metadata, implementation: G[Request] => Stream[F, Response])(implicit
      F: Async[F]
  ): Unit =
    unsafeRun(handleStreamResponse(headers, implementation(source)))
}
