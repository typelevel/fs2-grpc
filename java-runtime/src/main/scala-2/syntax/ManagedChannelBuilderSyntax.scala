package org.lyranthe.fs2_grpc
package java_runtime
package syntax

import cats.effect.{Resource, Sync}
import fs2.Stream
import io.grpc.{ManagedChannel, ManagedChannelBuilder}

trait ManagedChannelBuilderSyntax {
  implicit final def fs2GrpcSyntaxManagedChannelBuilder[MCB <: ManagedChannelBuilder[MCB]](
      builder: MCB
  ): ManagedChannelBuilderOps[MCB] =
    new ManagedChannelBuilderOps[MCB](builder)
}

final class ManagedChannelBuilderOps[MCB <: ManagedChannelBuilder[MCB]](val builder: MCB) extends AnyVal {

  /** Builds a `ManagedChannel` into a bracketed stream. The managed channel is
    * shut down when the stream is complete.  Shutdown is as follows:
    *
    * 1. We request an orderly shutdown, allowing preexisting calls to continue
    *    without accepting new calls.
    * 2. We block for up to 30 seconds on termination, using the blocking context
    * 3. If the channel is not yet terminated, we trigger a forceful shutdown
    *
    * For different tradeoffs in shutdown behavior, see {{resourceWithShutdown}}.
    */
  def resource[F[_]: Sync]: Resource[F, ManagedChannel] =
    channelResource[F, MCB](builder)

  /** Builds a `ManagedChannel` into a bracketed resource. The managed channel is
    * shut down when the resource is released.
    *
    * @param shutdown Determines the behavior of the cleanup of the managed
    * channel, with respect to forceful vs. graceful shutdown and how to poll
    * or block for termination.
    */
  def resourceWithShutdown[F[_]: Sync](shutdown: ManagedChannel => F[Unit]): Resource[F, ManagedChannel] =
    channelResourceWithShutdown[F, MCB](builder)(shutdown)

  /** Builds a `ManagedChannel` into a bracketed stream. The managed channel is
    * shut down when the resource is released.  Shutdown is as follows:
    *
    * 1. We request an orderly shutdown, allowing preexisting calls to continue
    *    without accepting new calls.
    * 2. We block for up to 30 seconds on termination, using the blocking context
    * 3. If the channel is not yet terminated, we trigger a forceful shutdown
    *
    * For different tradeoffs in shutdown behavior, see {{streamWithShutdown}}.
    */
  def stream[F[_]: Sync]: Stream[F, ManagedChannel] =
    Stream.resource(resource[F])

  /** Builds a `ManagedChannel` into a bracketed stream. The managed channel is
    * shut down when the stream is complete.
    *
    * @param shutdown Determines the behavior of the cleanup of the managed
    * channel, with respect to forceful vs. graceful shutdown and how to poll
    * or block for termination.
    */
  def streamWithShutdown[F[_]: Sync](shutdown: ManagedChannel => F[Unit]): Stream[F, ManagedChannel] =
    Stream.resource(resourceWithShutdown[F](shutdown))
}
