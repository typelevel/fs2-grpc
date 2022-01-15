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
package syntax

import java.util.concurrent.TimeUnit
import scala.concurrent._
import cats.effect._
import io.grpc.{Server, ServerBuilder}

trait ServerBuilderSyntax {
  implicit final def fs2GrpcSyntaxServerBuilder[SB <: ServerBuilder[SB]](builder: SB): ServerBuilderOps[SB] =
    new ServerBuilderOps[SB](builder)
}

final class ServerBuilderOps[SB <: ServerBuilder[SB]](val builder: SB) extends AnyVal {

  /** Builds a `Server` into a resource. The server is shut down when the resource is released. Shutdown is as follows:
    *
    *   1. We request an orderly shutdown, allowing preexisting calls to continue without accepting new calls. 2. We
    *      block for up to 30 seconds on termination, using the blocking context 3. If the server is not yet terminated,
    *      we trigger a forceful shutdown
    *
    * For different tradeoffs in shutdown behavior, see {{resourceWithShutdown}}.
    */
  def resource[F[_]](implicit F: Sync[F]): Resource[F, Server] =
    resourceWithShutdown { server =>
      F.delay {
        server.shutdown()
        if (!blocking(server.awaitTermination(30, TimeUnit.SECONDS))) {
          server.shutdownNow()
          ()
        }
      }
    }

  /** Builds a `Server` into a resource. The server is shut down when the resource is released.
    *
    * @param shutdown
    *   Determines the behavior of the cleanup of the server, with respect to forceful vs. graceful shutdown and how to
    *   poll or block for termination.
    */
  def resourceWithShutdown[F[_]](shutdown: Server => F[Unit])(implicit F: Sync[F]): Resource[F, Server] =
    Resource.make(F.delay(builder.build()))(shutdown)

  /** Builds a `Server` into a stream. The server is shut down when the stream is complete. Shutdown is as follows:
    *
    *   1. We request an orderly shutdown, allowing preexisting calls to continue without accepting new calls. 2. We
    *      block for up to 30 seconds on termination, using the blocking context 3. If the server is not yet terminated,
    *      we trigger a forceful shutdown
    *
    * For different tradeoffs in shutdown behavior, see {{streamWithShutdown}}.
    */
  def stream[F[_]](implicit F: Async[F]): Stream[F, Server] =
    Stream.resource(resource[F])

  /** Builds a `Server` into a stream. The server is shut down when the stream is complete.
    *
    * @param shutdown
    *   Determines the behavior of the cleanup of the server, with respect to forceful vs. graceful shutdown and how to
    *   poll or block for termination.
    */
  def streamWithShutdown[F[_]](shutdown: Server => F[Unit])(implicit F: Async[F]): Stream[F, Server] =
    Stream.resource(resourceWithShutdown(shutdown))
}
