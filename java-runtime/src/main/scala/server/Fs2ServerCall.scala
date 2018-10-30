package org.lyranthe.fs2_grpc
package java_runtime
package server

import cats.effect._
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import io.grpc._

// TODO: Add attributes, compression, message compression.
private[server] class Fs2ServerCall[F[_], Request, Response](val call: ServerCall[Request, Response],
                                                             val wakeOnReady: Ref[F, Option[Deferred[F, Unit]]]) {
  def onReady()(implicit F: Sync[F]): F[Unit] = {
    wakeOnReady
      .modify({
        case None       => (None, F.unit)
        case Some(wake) => (None, wake.complete(()))
      })
      .flatten
  }

  def isReady(implicit F: Sync[F]): F[Boolean] =
    F.delay(call.isReady)

  def sendHeaders(headers: Metadata)(implicit F: Sync[F]): F[Unit] =
    F.delay(call.sendHeaders(headers))

  def closeStream(status: Status, trailers: Metadata)(implicit F: Sync[F]): F[Unit] =
    F.delay(call.close(status, trailers))

  def sendMessage(message: Response)(implicit F: Sync[F]): F[Unit] =
    F.delay(call.sendMessage(message))

  def sendMessageOrDelay(message: Response)(implicit F: Concurrent[F]): F[Unit] =
    isReady.ifM(
      sendMessage(message), {
        Deferred[F, Unit].flatMap { wakeup =>
          wakeOnReady.set(wakeup.some) *>
            isReady.ifM(sendMessage(message), wakeup.get *> sendMessage(message))
        }
      }
    )

  def request(numMessages: Int)(implicit F: Sync[F]): F[Unit] =
    F.delay(call.request(numMessages))
}

private[server] object Fs2ServerCall {
  def apply[F[_], Request, Response](call: ServerCall[Request, Response])(
      implicit F: Concurrent[F]): F[Fs2ServerCall[F, Request, Response]] =
    for {
      wakeOnReady <- Ref[F].of(none[Deferred[F, Unit]])
    } yield new Fs2ServerCall[F, Request, Response](call, wakeOnReady)
}
