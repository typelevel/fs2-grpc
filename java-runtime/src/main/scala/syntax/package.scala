package org.lyranthe.fs2_grpc
package java_runtime

import cats.effect._
import io.grpc.{ManagedChannel, ManagedChannelBuilder, Server, ServerBuilder}
import java.util.concurrent.TimeUnit
import scala.concurrent._

package object syntax {

  object all extends AllSyntax
  object managedChannelBuilder extends ManagedChannelBuilderSyntax
  object serverBuilder extends ServerBuilderSyntax

  /// Shared helpers

  private[syntax] def serverResource[F[_]: Sync, SB <: ServerBuilder[SB]](builder: SB): Resource[F, Server] =
    serverResourceWithShutdown[F, SB](builder) { server =>
      Sync[F].delay {
        server.shutdown()
        if (!blocking(server.awaitTermination(30, TimeUnit.SECONDS))) {
          server.shutdownNow()
          ()
        }
      }
    }

  private[syntax] def serverResourceWithShutdown[F[_]: Sync, SB <: ServerBuilder[SB]](builder: SB)(
      shutdown: Server => F[Unit]
  ): Resource[F, Server] =
    Resource.make(Sync[F].delay(builder.build()))(shutdown)

  //

  private[syntax] def channelResource[F[_]: Sync, MCB <: ManagedChannelBuilder[MCB]](
      builder: MCB
  ): Resource[F, ManagedChannel] =
    channelResourceWithShutdown[F, MCB](builder) { ch =>
      Sync[F].delay {
        ch.shutdown()
        if (!blocking(ch.awaitTermination(30, TimeUnit.SECONDS))) {
          ch.shutdownNow()
          ()
        }
      }
    }

  private[syntax] def channelResourceWithShutdown[F[_]: Sync, MCB <: ManagedChannelBuilder[MCB]](builder: MCB)(
      shutdown: ManagedChannel => F[Unit]
  ): Resource[F, ManagedChannel] =
    Resource.make(Sync[F].delay(builder.build()))(shutdown)

}
