package org.lyranthe.fs2_grpc
package java_runtime
package server

import cats.effect._
import cats.syntax.functor._
import io.grpc._
import org.lyranthe.fs2_grpc.java_runtime.shared.Readiness

// TODO: Add attributes, compression, message compression.
private[server] class Fs2ServerCall[F[_], Request, Response](val call: ServerCall[Request, Response]) extends AnyVal {
  def sendHeaders(headers: Metadata)(implicit F: Sync[F]): F[Unit] =
    F.delay(call.sendHeaders(headers))

  def closeStream(status: Status, trailers: Metadata)(implicit F: Sync[F]): F[Unit] =
    F.delay(call.close(status, trailers))

  private def isReady(implicit F: Sync[F]): F[Boolean] = F.delay(call.isReady)

  def sendMessageWhenReady(readiness: Readiness[F])(implicit F: Sync[F]): Response => F[Unit] =
    message => readiness.whenReady(isReady, sendMessageImmediately(message))

  def sendMessageImmediately(message: Response)(implicit F: Sync[F]): F[Unit] =
    F.delay(call.sendMessage(message))

  def request(numMessages: Int)(implicit F: Sync[F]): F[Unit] =
    F.delay(call.request(numMessages))
}

private[server] object Fs2ServerCall {

  def apply[F[_]: Sync, Request, Response](
      call: ServerCall[Request, Response],
      options: ServerCallOptions
  ): F[Fs2ServerCall[F, Request, Response]] =
    Sync[F]
      .delay(options.compressor.map(_.name).foreach(call.setCompression))
      .as(new Fs2ServerCall[F, Request, Response](call))
}
