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

import cats.effect.Async
import cats.effect.SyncIO
import fs2._
import fs2.grpc.server.ServerCallOptions
import fs2.grpc.server.ServerOptions
import fs2.grpc.server.internal.Fs2ServerCall.Cancel
import io.grpc.ServerCall
import io.grpc._

object Fs2StreamServerCallHandler {

  private def mkListener[Request](
      channel: OneShotChannel[Request],
      cancel: Cancel
  ): ServerCall.Listener[Request] =
    new ServerCall.Listener[Request] {
      override def onCancel(): Unit =
        cancel.unsafeRunSync()

      override def onMessage(message: Request): Unit =
        channel.send(message).unsafeRunSync()

      override def onHalfClose(): Unit =
        channel.close().unsafeRunSync()
    }

  def mkHandler[F[_]: Async, G[_], Request, Response](
      impl: (Stream[F, Request], Metadata) => G[Response],
      options: ServerOptions
  )(start: (Fs2ServerCall[Request, Response], G[Response]) => SyncIO[Cancel]): ServerCallHandler[Request, Response] =
    new ServerCallHandler[Request, Response] {
      private val opt = options.callOptionsFn(ServerCallOptions.default)

      def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        for {
          call <- Fs2ServerCall.setup(opt, call)
          _ <- call.request(1) // prefetch size
          channel <- OneShotChannel.empty[Request]
          cancel <- start(call, impl(channel.stream.through(call.requestOnPull), headers))
        } yield mkListener(channel, cancel)
      }.unsafeRunSync()
    }
}
