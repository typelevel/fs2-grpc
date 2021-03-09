package org.lyranthe.fs2_grpc
package java_runtime
package syntax

import cats.effect.{Resource, Sync}
import fs2.Stream
import io.grpc.{Server, ServerBuilder}

trait ServerBuilderSyntax {
  implicit final def fs2GrpcSyntaxServerBuilder[SB <: ServerBuilder[SB]](builder: SB): ServerBuilderOps[SB] =
    new ServerBuilderOps[SB](builder)
}

final class ServerBuilderOps[SB <: ServerBuilder[SB]](val builder: SB) extends AnyVal {

  /** Builds a `Server` into a bracketed resource. The server is shut
    * down when the resource is released. Shutdown is as follows:
    *
    * 1. We request an orderly shutdown, allowing preexisting calls to continue
    *    without accepting new calls.
    * 2. We block for up to 30 seconds on termination, using the blocking context
    * 3. If the server is not yet terminated, we trigger a forceful shutdown
    *
    * For different tradeoffs in shutdown behavior, see {{resourceWithShutdown}}.
    */
  def resource[F[_]: Sync]: Resource[F, Server] =
    serverResource[F, SB](builder)

  /** Builds a `Server` into a bracketed resource. The server is shut
    * down when the resource is released.
    *
    * @param shutdown Determines the behavior of the cleanup of the
    * server, with respect to forceful vs. graceful shutdown and how
    * to poll or block for termination.
    */
  def resourceWithShutdown[F[_]: Sync](shutdown: Server => F[Unit]): Resource[F, Server] =
    serverResourceWithShutdown[F, SB](builder)(shutdown)

  /** Builds a `Server` into a bracketed stream. The server is shut
    * down when the stream is complete.  Shutdown is as follows:
    *
    * 1. We request an orderly shutdown, allowing preexisting calls to continue
    *    without accepting new calls.
    * 2. We block for up to 30 seconds on termination, using the blocking context
    * 3. If the server is not yet terminated, we trigger a forceful shutdown
    *
    * For different tradeoffs in shutdown behavior, see {{streamWithShutdown}}.
    */
  def stream[F[_]: Sync]: Stream[F, Server] =
    Stream.resource(resource[F])

  /** Builds a `Server` into a bracketed stream. The server is shut
    * down when the stream is complete.
    *
    * @param shutdown Determines the behavior of the cleanup of the
    * server, with respect to forceful vs. graceful shutdown and how
    * to poll or block for termination.
    */
  def streamWithShutdown[F[_]: Sync](shutdown: Server => F[Unit]): Stream[F, Server] =
    Stream.resource(resourceWithShutdown[F](shutdown))
}
