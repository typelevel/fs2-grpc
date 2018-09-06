package org.lyranthe.fs2_grpc.java_runtime.server

import cats.effect._
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import io.grpc._

class Fs2UnaryServerCallListener[F[_], Request, Response] private (
    value: Ref[IO, Option[Request]],
    isComplete: Deferred[IO, Unit],
    val call: Fs2ServerCall[F, Request, Response])(implicit F: Concurrent[F])
    extends ServerCall.Listener[Request]
    with Fs2ServerCallListener[F, F, Request, Response] {
  override def onMessage(message: Request): Unit = {
    value.access
      .flatMap {
        case (curValue, modify) =>
          if (curValue.isDefined)
            IO.raiseError(
              new StatusRuntimeException(Status.INTERNAL
                .withDescription(Fs2UnaryServerCallListener.TooManyRequests)))
          else
            modify(message.some)
      }
      .unsafeRunSync()
    ()
  }

  override def onHalfClose(): Unit = isComplete.complete(()).unsafeRunSync()

  override def source: F[Request] =
    F.liftIO(for {
      _           <- isComplete.get
      valueOrNone <- value.get
      value <- valueOrNone.fold[IO[Request]](
        IO.raiseError(
          new StatusRuntimeException(Status.INTERNAL.withDescription(Fs2UnaryServerCallListener.NoMessage))))(IO.pure)
    } yield value)
}

object Fs2UnaryServerCallListener {
  final val TooManyRequests: String = "Too many requests"
  final val NoMessage: String       = "No message for unary call"

  class PartialFs2UnaryServerCallListener[F[_]](val dummy: Boolean = false) extends AnyVal {
    def unsafeCreate[Request, Response](call: ServerCall[Request, Response])(
        implicit F: Concurrent[F],
        cs: ContextShift[IO]): Fs2UnaryServerCallListener[F, Request, Response] = {
      val listener = for {
        ref     <- Ref[IO].of(none[Request])
        promise <- Deferred[IO, Unit]
      } yield
        new Fs2UnaryServerCallListener[F, Request, Response](ref, promise, Fs2ServerCall[F, Request, Response](call))

      listener.unsafeRunSync()
    }
  }

  def apply[F[_]] = new PartialFs2UnaryServerCallListener[F]
}
