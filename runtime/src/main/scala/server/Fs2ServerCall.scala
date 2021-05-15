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

import cats.effect._
import io.grpc._

private[server] class Fs2ServerCall[F[_], Request, Response](val call: ServerCall[Request, Response]) extends AnyVal {
  def sendHeaders(headers: Metadata)(implicit F: Sync[F]): F[Unit] =
    F.delay(call.sendHeaders(headers))

  def closeStream(status: Status, trailers: Metadata)(implicit F: Sync[F]): F[Unit] =
    F.delay(call.close(status, trailers))

  def sendMessage(message: Response)(implicit F: Sync[F]): F[Unit] =
    F.delay(call.sendMessage(message))

  def request(numMessages: Int)(implicit F: Sync[F]): F[Unit] =
    F.delay(call.request(numMessages))
}

private[server] object Fs2ServerCall {

  def apply[F[_]: Sync, Request, Response](
      call: ServerCall[Request, Response],
      options: ServerOptions
  ): F[Fs2ServerCall[F, Request, Response]] = Sync[F].delay {
    val callOptions = options.callOptionsFn(ServerCallOptions.default)

    call.setMessageCompression(callOptions.messageCompression)
    callOptions.compressor.map(_.name).foreach(call.setCompression)

    new Fs2ServerCall[F, Request, Response](call)
  }

}
