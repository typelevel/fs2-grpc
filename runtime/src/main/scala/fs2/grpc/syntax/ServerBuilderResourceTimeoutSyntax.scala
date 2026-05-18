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
import cats.effect._
import cats.syntax.all._
import io.grpc.{Server, ServerBuilder}

import scala.concurrent.duration._

trait ServerBuilderResourceTimeoutSyntax {
  implicit final def fs2GrpcSyntaxServerBuilderResourceTimeout[SB <: ServerBuilder[SB]](
      builder: SB
  ): ServerBuilderResourceTimeoutOps[SB] =
    new ServerBuilderResourceTimeoutOps[SB](builder)
}

final class ServerBuilderResourceTimeoutOps[SB <: ServerBuilder[SB]](val builder: SB) extends AnyVal {

  /** Builds a `Server` into a resource. The server is shut down when the resource is released. Shutdown is as follows:
    *
    *   i. We request an orderly shutdown, allowing preexisting calls to continue without accepting new calls.
    *   i. We block for up to {timeout} on termination, using the blocking context
    *   i. If the server is not yet terminated, we trigger a forceful shutdown
    *
    * @param timeout
    *   the duration of the timeout
    */
  def resourceWithShutdownTimeout[F[_]](timeout: FiniteDuration)(implicit F: Sync[F]): Resource[F, Server] =
    new ServerBuilderOps[SB](builder).resourceWithShutdown { server =>
      for {
        _ <- F.delay(server.shutdown())
        terminated <- F.interruptible(server.awaitTermination(timeout.toSeconds, TimeUnit.SECONDS))
        _ <- F.unlessA(terminated)(F.delay(server.shutdownNow()))
      } yield ()
    }

  /** Builds a `Server` into a stream. The server is shut down when the stream is complete. Shutdown is as follows:
    *
    *   i. We request an orderly shutdown, allowing preexisting calls to continue without accepting new calls.
    *   i. We block for up to {timeout} on termination, using the blocking context
    *   i. If the server is not yet terminated, we trigger a forceful shutdown
    *
    * @param timeout
    *   the duration of the timeout
    */
  def streamWithShutdownTimeout[F[_]](timeout: FiniteDuration)(implicit F: Sync[F]): Stream[F, Server] =
    Stream.resource(resourceWithShutdownTimeout[F](timeout))

}
