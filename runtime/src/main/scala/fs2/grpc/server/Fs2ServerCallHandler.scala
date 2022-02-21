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
import cats.effect.std.Dispatcher
import fs2.grpc.server.internal.Fs2StreamServerCallHandler
import fs2.grpc.server.internal.Fs2UnaryServerCallHandler
import io.grpc._

class Fs2ServerCallHandler[F[_]: Async] private (
    dispatcher: Dispatcher[F],
    options: ServerOptions
) {

  def unaryToUnaryCall[Request, Response](
      implementation: (Request, Metadata) => F[Response]
  ): ServerCallHandler[Request, Response] =
    Fs2UnaryServerCallHandler.mkHandler(implementation, options)(_.unary(_, dispatcher))

  def unaryToStreamingCall[Request, Response](
      implementation: (Request, Metadata) => Stream[F, Response]
  ): ServerCallHandler[Request, Response] =
    Fs2UnaryServerCallHandler.mkHandler(implementation, options)(_.stream(_, dispatcher))

  def streamingToUnaryCall[Request, Response](
      implementation: (Stream[F, Request], Metadata) => F[Response]
  ): ServerCallHandler[Request, Response] =
    Fs2StreamServerCallHandler.mkHandler(implementation, options)(_.unary(_, dispatcher))

  def streamingToStreamingCall[Request, Response](
      implementation: (Stream[F, Request], Metadata) => Stream[F, Response]
  ): ServerCallHandler[Request, Response] =
    Fs2StreamServerCallHandler.mkHandler(implementation, options)(_.stream(_, dispatcher))
}

object Fs2ServerCallHandler {

  def apply[F[_]: Async](
      dispatcher: Dispatcher[F],
      serverOptions: ServerOptions
  ): Fs2ServerCallHandler[F] =
    new Fs2ServerCallHandler[F](dispatcher, serverOptions)
}
