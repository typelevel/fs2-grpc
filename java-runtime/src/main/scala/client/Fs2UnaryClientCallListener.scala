package org.lyranthe.fs2_grpc
package java_runtime
package client

import cats.MonadError
import cats.effect._
import cats.effect.kernel.{Deferred, Ref}
import cats.effect.std.Dispatcher
import cats.implicits._
import io.grpc._

class Fs2UnaryClientCallListener[F[_], Response] private (
    grpcStatus: Deferred[F, GrpcStatus],
    value: Ref[F, Option[Response]],
    runner: UnsafeRunner[F]
)(implicit F: MonadError[F, Throwable])
    extends ClientCall.Listener[Response] {

  override def onClose(status: Status, trailers: Metadata): Unit =
    runner.unsafeRunSync(grpcStatus.complete(GrpcStatus(status, trailers)).void)

  override def onMessage(message: Response): Unit =
    runner.unsafeRunSync(value.set(message.some).void)

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

object Fs2UnaryClientCallListener {

  def apply[F[_]: Async, Response](dispatcher: Dispatcher[F]): F[Fs2UnaryClientCallListener[F, Response]] =
    apply[F, Response](UnsafeRunner[F](dispatcher))

  private[client] def apply[F[_]: Async, Response](
      runner: UnsafeRunner[F]
  ): F[Fs2UnaryClientCallListener[F, Response]] =
    (Deferred[F, GrpcStatus], Ref.of[F, Option[Response]](none)).mapN((response, value) =>
      new Fs2UnaryClientCallListener[F, Response](response, value, runner)
    )
}
