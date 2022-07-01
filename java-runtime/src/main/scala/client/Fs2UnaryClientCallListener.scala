package org.lyranthe.fs2_grpc
package java_runtime
package client

import cats.effect._
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import io.grpc._

private[client] class Fs2UnaryClientCallListener[F[_], Response](
    grpcStatus: Deferred[F, GrpcStatus],
    value: Ref[F, Option[Response]],
    signalReadiness: F[Unit]
)(implicit
    F: Effect[F]
) extends ClientCall.Listener[Response] {

  override def onReady(): Unit = signalReadiness.unsafeRun()

  override def onClose(status: Status, trailers: Metadata): Unit =
    grpcStatus.complete(GrpcStatus(status, trailers)).unsafeRun()

  override def onMessage(message: Response): Unit =
    value.set(message.some).unsafeRun()

  def getValue: F[Response] = {
    for {
      r <- grpcStatus.get
      v <- value.get
      result <- {
        if (!r.status.isOk)
          F.raiseError(r.status.asRuntimeException(r.trailers))
        else {
          v match {
            case None =>
              F.raiseError(
                Status.INTERNAL
                  .withDescription("No value received for unary call")
                  .asRuntimeException(r.trailers)
              )
            case Some(v1) =>
              F.pure(v1)
          }
        }
      }
    } yield result
  }
}

private[client] object Fs2UnaryClientCallListener {

  def apply[F[_]: ConcurrentEffect, Response](signalReadiness: F[Unit]): F[Fs2UnaryClientCallListener[F, Response]] = {
    (Deferred[F, GrpcStatus], Ref.of[F, Option[Response]](none)).mapN((response, value) =>
      new Fs2UnaryClientCallListener[F, Response](response, value, signalReadiness)
    )
  }

}
