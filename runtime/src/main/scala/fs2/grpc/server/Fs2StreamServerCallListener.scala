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
package server

import cats.Functor
import cats.syntax.all._
import cats.effect.kernel.Deferred
import cats.effect.{Async, SyncIO}
import cats.effect.std.Dispatcher
import fs2.grpc.client.StreamIngest
import io.grpc.ServerCall

class Fs2StreamServerCallListener[F[_], Request, Response] private (
    ingest: StreamIngest[F, Request],
    signalReadiness: SyncIO[Unit],
    val isCancelled: Deferred[F, Unit],
    val call: Fs2ServerCall[F, Request, Response],
    val dispatcher: Dispatcher[F]
)(implicit F: Functor[F])
    extends ServerCall.Listener[Request]
    with Fs2ServerCallListener[F, Stream[F, *], Request, Response] {

  override def onCancel(): Unit =
    dispatcher.unsafeRunSync(isCancelled.complete(()).void)

  override def onMessage(message: Request): Unit =
    dispatcher.unsafeRunSync(ingest.onMessage(message))

  override def onReady(): Unit = signalReadiness.unsafeRunSync()

  override def onHalfClose(): Unit =
    dispatcher.unsafeRunSync(ingest.onClose(None))

  override def source: Stream[F, Request] = ingest.messages
}

object Fs2StreamServerCallListener {

  class PartialFs2StreamServerCallListener[F[_]](val dummy: Boolean = false) extends AnyVal {

    private[server] def apply[Request, Response](
        call: ServerCall[Request, Response],
        signalReadiness: SyncIO[Unit],
        dispatcher: Dispatcher[F],
        options: ServerOptions
    )(implicit F: Async[F]): F[Fs2StreamServerCallListener[F, Request, Response]] = for {
      isCancelled <- Deferred[F, Unit]
      request = (n: Int) => F.delay(call.request(n))
      ingest <- StreamIngest[F, Request](request, prefetchN = 1)
      serverCall <- Fs2ServerCall[F, Request, Response](call, options)
    } yield new Fs2StreamServerCallListener[F, Request, Response](
      ingest,
      signalReadiness,
      isCancelled,
      serverCall,
      dispatcher
    )

  }

  private[server] def apply[F[_]] = new PartialFs2StreamServerCallListener[F]

}
