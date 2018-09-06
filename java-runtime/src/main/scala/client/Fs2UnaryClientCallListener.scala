package org.lyranthe.fs2_grpc.java_runtime.client

import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{ContextShift, IO, LiftIO}
import cats.implicits._
import io.grpc._

class Fs2UnaryClientCallListener[Response](grpcStatus: Deferred[IO, GrpcStatus], value: Ref[IO, Option[Response]])
    extends ClientCall.Listener[Response] {
  override def onClose(status: Status, trailers: Metadata): Unit =
    grpcStatus.complete(GrpcStatus(status, trailers)).unsafeRunSync()

  override def onMessage(message: Response): Unit =
    value.set(message.some).unsafeRunSync()

  def getValue[F[_]](implicit F: LiftIO[F]): F[Response] = {
    val result: IO[Response] = for {
      r <- grpcStatus.get
      v <- value.get
      result <- {
        if (!r.status.isOk)
          IO.raiseError(r.status.asRuntimeException(r.trailers))
        else {
          v match {
            case None =>
              IO.raiseError(
                Status.INTERNAL
                  .withDescription("No value received for unary call")
                  .asRuntimeException(r.trailers))
            case Some(v1) =>
              IO.pure(v1)
          }
        }
      }
    } yield result

    F.liftIO(result)
  }
}

object Fs2UnaryClientCallListener {
  def apply[F[_], Response](implicit F: LiftIO[F], cs: ContextShift[IO]): F[Fs2UnaryClientCallListener[Response]] = {
    F.liftIO(for {
      response <- Deferred[IO, GrpcStatus]
      value    <- Ref[IO].of(none[Response])
    } yield new Fs2UnaryClientCallListener[Response](response, value))
  }
}
