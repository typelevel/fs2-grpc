package org.lyranthe.fs2_grpc
package java_runtime
package client

import cats.implicits._
import cats.effect._
import cats.effect.concurrent.{Deferred, Ref}
import io.grpc.{ClientCall, Metadata, Status, StatusRuntimeException}

class Fs2UnaryClientCallListener[F[_], Response](
  grpcStatus: Deferred[F, GrpcStatus],
  value: Ref[F, Option[Response]],
  errorAdapter: StatusRuntimeException => Option[Exception]
)(implicit F: Effect[F]) extends ClientCall.Listener[Response] {

  import Fs2UnaryClientCallListener._

  override def onClose(status: Status, trailers: Metadata): Unit =
    grpcStatus.complete(GrpcStatus(status, trailers)).unsafeRun()

  override def onMessage(message: Response): Unit =
    value.set(message.some).unsafeRun()

  def getValue: F[Response] = {
     for {
      r <- grpcStatus.get
      v <- value.get
      result <- {
        if (!r.status.isOk) {
          val str = r.status.asRuntimeException(r.trailers)
          F.raiseError[Response](errorAdapter(str).getOrElse(str))
        } else
          v.fold(F.raiseError[Response](noValueReceivedError(r.trailers)))(F.pure)
      }
    } yield result
  }
}

object Fs2UnaryClientCallListener {

  private val noValueReceivedError: Metadata => StatusRuntimeException =
    md => Status.INTERNAL.withDescription("No value received for unary call").asRuntimeException(md)

  def apply[F[_]: ConcurrentEffect, Response](
    errorAdapter: StatusRuntimeException => Option[Exception]
  ): F[Fs2UnaryClientCallListener[F, Response]] = {
    (Deferred[F, GrpcStatus], Ref.of[F, Option[Response]](none)).mapN((response, value) =>
      new Fs2UnaryClientCallListener[F, Response](response, value, errorAdapter)
    )
  }

}
