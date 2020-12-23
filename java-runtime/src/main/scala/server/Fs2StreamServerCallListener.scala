package org.lyranthe.fs2_grpc
package java_runtime
package server

import cats.Functor
import cats.syntax.all._
import cats.effect.kernel.Deferred
import cats.effect.Async
import cats.effect.std.{Dispatcher, Queue}
import io.grpc.ServerCall
import fs2._

class Fs2StreamServerCallListener[F[_], Request, Response] private (
    requestQ: Queue[F, Option[Request]],
    val isCancelled: Deferred[F, Unit],
    val call: Fs2ServerCall[F, Request, Response],
    val dispatcher: Dispatcher[F]
)(implicit F: Functor[F])
    extends ServerCall.Listener[Request]
    with Fs2ServerCallListener[F, Stream[F, *], Request, Response] {

  override def onCancel(): Unit =
    dispatcher.unsafeRunSync(isCancelled.complete(()).void)

  override def onMessage(message: Request): Unit = {
    call.call.request(1)
    dispatcher.unsafeRunSync(requestQ.offer(message.some))
  }

  override def onHalfClose(): Unit =
    dispatcher.unsafeRunSync(requestQ.offer(none))

  override def source: Stream[F, Request] =
    Stream.repeatEval(requestQ.take).unNoneTerminate
}

object Fs2StreamServerCallListener {

  class PartialFs2StreamServerCallListener[F[_]](val dummy: Boolean = false) extends AnyVal {

    private[server] def apply[Request, Response](
        call: ServerCall[Request, Response],
        dispatcher: Dispatcher[F],
        options: ServerCallOptions = ServerCallOptions.default
    )(implicit F: Async[F]): F[Fs2StreamServerCallListener[F, Request, Response]] = for {
      inputQ <- Queue.unbounded[F, Option[Request]]
      isCancelled <- Deferred[F, Unit]
      serverCall <- Fs2ServerCall[F, Request, Response](call, options)
    } yield new Fs2StreamServerCallListener[F, Request, Response](inputQ, isCancelled, serverCall, dispatcher)

  }

  private[server] def apply[F[_]] = new PartialFs2StreamServerCallListener[F]

}
