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

package fs2.grpc.server

import cats.effect._
import cats.effect.std.Dispatcher
import io.grpc._

object Fs2StatefulServerCall {
  type Cancel = () => Any

  def setup[F[_], I, O](
      options: ServerCallOptions,
      call: ServerCall[I, O],
      dispatcher: Dispatcher[F]
  ): Fs2StatefulServerCall[F, I, O] = {
    call.setMessageCompression(options.messageCompression)
    options.compressor.map(_.name).foreach(call.setCompression)
    new Fs2StatefulServerCall[F, I, O](call, dispatcher)
  }
}

final class Fs2StatefulServerCall[F[_], Request, Response](
    call: ServerCall[Request, Response],
    dispatcher: Dispatcher[F]
) {

  import Fs2StatefulServerCall.Cancel

  def stream(response: fs2.Stream[F, Response])(implicit F: Sync[F]): Cancel =
    run(response.map(sendMessage).compile.drain)

  def unary(response: F[Response])(implicit F: Sync[F]): Cancel =
    run(F.map(response)(sendMessage))

  private var sentHeader: Boolean = false

  private def sendMessage(message: Response): Unit =
    if (!sentHeader) {
      sentHeader = true
      call.sendHeaders(new Metadata())
      call.sendMessage(message)
    } else {
      call.sendMessage(message)
    }

  private def run(completed: F[Unit])(implicit F: Sync[F]): Cancel =
    dispatcher.unsafeRunCancelable(F.guaranteeCase(completed) {
      case Outcome.Succeeded(_) => closeStream(Status.OK, new Metadata())
      case Outcome.Errored(e) =>
        e match {
          case ex: StatusException =>
            closeStream(ex.getStatus, Option(ex.getTrailers).getOrElse(new Metadata()))
          case ex: StatusRuntimeException =>
            closeStream(ex.getStatus, Option(ex.getTrailers).getOrElse(new Metadata()))
          case ex =>
            closeStream(Status.INTERNAL.withDescription(ex.getMessage).withCause(ex), new Metadata())
        }
      case Outcome.Canceled() => closeStream(Status.CANCELLED, new Metadata())
    })

  private def closeStream(status: Status, metadata: Metadata)(implicit F: Sync[F]): F[Unit] =
    F.delay(call.close(status, metadata))
}
