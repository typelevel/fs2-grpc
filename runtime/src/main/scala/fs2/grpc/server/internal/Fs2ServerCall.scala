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

package fs2.grpc.server.internal

import cats.effect._
import cats.effect.std.Dispatcher
import fs2._
import fs2.grpc.server.ServerCallOptions
import io.grpc._

private[server] object Fs2ServerCall {
  type Cancel = SyncIO[Unit]

  def setup[I, O](
      options: ServerCallOptions,
      call: ServerCall[I, O]
  ): SyncIO[Fs2ServerCall[I, O]] =
    SyncIO {
      call.setMessageCompression(options.messageCompression)
      options.compressor.map(_.name).foreach(call.setCompression)
      new Fs2ServerCall[I, O](call)
    }
}

private[server] final class Fs2ServerCall[Request, Response](
    call: ServerCall[Request, Response]
) {

  import Fs2ServerCall.Cancel

  def stream[F[_]](response: Stream[F, Response], dispatcher: Dispatcher[F])(implicit F: Sync[F]): SyncIO[Cancel] =
    run(
      response.pull.peek1
        .flatMap {
          case Some((_, stream)) =>
            Pull.suspend {
              call.sendHeaders(new Metadata())
              stream.map(call.sendMessage).pull.echo
            }
          case None => Pull.done
        }
        .stream
        .compile
        .drain,
      dispatcher
    )

  def unary[F[_]](response: F[Response], dispatcher: Dispatcher[F])(implicit F: Sync[F]): SyncIO[Cancel] =
    run(
      F.map(response) { message =>
        call.sendHeaders(new Metadata())
        call.sendMessage(message)
      },
      dispatcher
    )

  def request(n: Int): SyncIO[Unit] =
    SyncIO(call.request(n))

  def close(status: Status, metadata: Metadata): SyncIO[Unit] =
    SyncIO(call.close(status, metadata))

  private def run[F[_]](completed: F[Unit], dispatcher: Dispatcher[F])(implicit F: Sync[F]): SyncIO[Cancel] = {
    SyncIO {
      val cancel = dispatcher.unsafeRunCancelable(F.guaranteeCase(completed) {
        case Outcome.Succeeded(_) => close(Status.OK, new Metadata()).to[F]
        case Outcome.Errored(e) => handleError(e).to[F]
        case Outcome.Canceled() => close(Status.CANCELLED, new Metadata()).to[F]
      })
      SyncIO(cancel()).void
    }
  }

  private def handleError(t: Throwable): SyncIO[Unit] = t match {
    case ex: StatusException => close(ex.getStatus, Option(ex.getTrailers).getOrElse(new Metadata()))
    case ex: StatusRuntimeException => close(ex.getStatus, Option(ex.getTrailers).getOrElse(new Metadata()))
    case ex => close(Status.INTERNAL.withDescription(ex.getMessage).withCause(ex), new Metadata())
  }
}
