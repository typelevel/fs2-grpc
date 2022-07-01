package org.lyranthe.fs2_grpc
package java_runtime
package server

import cats.effect.concurrent.Deferred
import cats.effect.{ConcurrentEffect, Effect}
import cats.implicits._
import fs2._
import io.grpc.ServerCall
import org.lyranthe.fs2_grpc.java_runtime.client.StreamIngest

class Fs2StreamServerCallListener[F[_], Request, Response] private (
    ingest: StreamIngest[F, Option[Request]],
    signalReadiness: F[Unit],
    val isCancelled: Deferred[F, Unit],
    val call: Fs2ServerCall[F, Request, Response]
)(implicit F: Effect[F])
    extends ServerCall.Listener[Request]
    with Fs2ServerCallListener[F, Stream[F, *], Request, Response] {

  override def onReady(): Unit = signalReadiness.unsafeRun()

  override def onCancel(): Unit = {
    isCancelled.complete(()).unsafeRun()
  }

  override def onMessage(message: Request): Unit = {
    ingest.onMessage(Some(message)).unsafeRun()
  }

  override def onHalfClose(): Unit = ingest.onMessage(none).unsafeRun()

  override def source: Stream[F, Request] =
    ingest.messages.unNoneTerminate
}

object Fs2StreamServerCallListener {
  class PartialFs2StreamServerCallListener[F[_]](val dummy: Boolean = false) extends AnyVal {

    def apply[Request, Response](
        call: ServerCall[Request, Response],
        signalReadiness: F[Unit],
        options: ServerCallOptions = ServerCallOptions.default
    )(implicit
        F: ConcurrentEffect[F]
    ): F[Fs2StreamServerCallListener[F, Request, Response]] = {
      for {
        isCancelled <- Deferred[F, Unit]
        serverCall <- Fs2ServerCall[F, Request, Response](call, options)
        ingest <- StreamIngest[F, Option[Request]](serverCall.request, options.prefetchN)
      } yield new Fs2StreamServerCallListener[F, Request, Response](ingest, signalReadiness, isCancelled, serverCall)
    }
  }

  def apply[F[_]] = new PartialFs2StreamServerCallListener[F]

}
