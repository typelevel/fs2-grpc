package org.lyranthe.fs2_grpc
package java_runtime
package client

import cats.syntax.all._
import cats.effect._
import fs2.Stream
import io.grpc.{ClientCall, Metadata, Status}

private[client] class Fs2StreamClientCallListener[F[_]: Effect, Response](
    ingest: StreamIngest[F, Response],
    signalReadiness: F[Unit],
) extends ClientCall.Listener[Response] {
  override def onReady(): Unit = signalReadiness.unsafeRun()

  override def onMessage(message: Response): Unit =
    ingest.onMessage(message).unsafeRun()

  override def onClose(status: Status, trailers: Metadata): Unit =
    ingest.onClose(GrpcStatus(status, trailers)).unsafeRun()

  val stream: Stream[F, Response] = ingest.messages

}

private[client] object Fs2StreamClientCallListener {

  def apply[F[_], Response](
      request: Int => F[Unit],
      signalReadiness: F[Unit],
      prefetchN: Int
  )(implicit F: ConcurrentEffect[F]): F[Fs2StreamClientCallListener[F, Response]] =
    StreamIngest[F, Response](request, prefetchN).map(streamIngest =>
      new Fs2StreamClientCallListener[F, Response](streamIngest, signalReadiness)
    )

}
