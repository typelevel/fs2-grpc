/*
 * Copyright (c) 2018 Gary Coady / Fs2 Grpc Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2
package grpc
package client

import cats.effect.SyncIO
import cats.implicits._
import cats.effect.kernel.Async
import fs2.grpc.shared.StreamIngest
import io.grpc.{ClientCall, Metadata, Status}

private[client] class Fs2StreamClientCallListener[F[_], Response] private (
    ingest: StreamIngest[F, Response],
    signalReadiness: SyncIO[Unit]
) extends ClientCall.Listener[Response] {

  override def onMessage(message: Response): Unit =
    ingest.unsafeOnMessage(message)

  override def onClose(status: Status, trailers: Metadata): Unit = {
    val error = if (status.isOk) None else Some(status.asRuntimeException(trailers))
    ingest.unsafeOnClose(error)
  }

  override def onReady(): Unit = signalReadiness.unsafeRunSync()

  val stream: Stream[F, Response] = ingest.messages
}

private[client] object Fs2StreamClientCallListener {

  def create[F[_]: Async, Response](
      request: Int => F[Unit],
      signalReadiness: SyncIO[Unit],
      prefetchN: Int
  ): F[Fs2StreamClientCallListener[F, Response]] =
    StreamIngest[F, Response](request, prefetchN).map(
      new Fs2StreamClientCallListener[F, Response](_, signalReadiness)
    )

}
