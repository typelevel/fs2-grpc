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

import cats.syntax.all._
import cats.effect.{Async, Resource}
import cats.effect.std.Dispatcher
import io.grpc._
import fs2.grpc.client.ClientOptions
import fs2.grpc.client.ClientAspect
import fs2.grpc.server.ServerOptions
import fs2.grpc.server.ServiceAspect

trait GeneratedCompanion[Service[*[_], _]] {

  implicit final def serviceCompanion: GeneratedCompanion[Service] = this

///=== Client ==========================================================================================================

  def mkClientFull[F[_], G[_]: Async, A](
      dispatcher: Dispatcher[G],
      channel: Channel,
      clientAspect: ClientAspect[F, G, A],
      clientOptions: ClientOptions
  ): Service[F, A]

  def mkClient[F[_]: Async, A](
      dispatcher: Dispatcher[F],
      channel: Channel,
      mkMetadata: A => F[Metadata],
      clientOptions: ClientOptions
  ): Service[F, A] =
    mkClientFull[F, F, A](
      dispatcher,
      channel,
      ClientAspect.default[F].contraModify(mkMetadata),
      clientOptions
    )

  final def mkClient[F[_]: Async, A](
      dispatcher: Dispatcher[F],
      channel: Channel,
      mkMetadata: A => F[Metadata]
  ): Service[F, A] =
    mkClient[F, A](dispatcher, channel, mkMetadata, ClientOptions.default)

  final def mkClientResource[F[_]: Async, A](
      channel: Channel,
      mkMetadata: A => F[Metadata],
      clientOptions: ClientOptions
  ): Resource[F, Service[F, A]] =
    Dispatcher.parallel[F].map(mkClient[F, A](_, channel, mkMetadata, clientOptions))

  final def mkClientResource[F[_]: Async, A](
      channel: Channel,
      mkMetadata: A => F[Metadata]
  ): Resource[F, Service[F, A]] =
    mkClientResource[F, A](channel, mkMetadata, ClientOptions.default)

  final def client[F[_]: Async, A](
      dispatcher: Dispatcher[F],
      channel: Channel,
      mkMetadata: A => Metadata,
      clientOptions: ClientOptions
  ): Service[F, A] =
    mkClient[F, A](dispatcher, channel, (a: A) => mkMetadata(a).pure[F], clientOptions)

  final def client[F[_]: Async, A](
      dispatcher: Dispatcher[F],
      channel: Channel,
      mkMetadata: A => Metadata
  ): Service[F, A] =
    mkClient[F, A](dispatcher, channel, (a: A) => mkMetadata(a).pure[F], ClientOptions.default)

  final def clientResource[F[_]: Async, A](
      channel: Channel,
      mkMetadata: A => Metadata,
      clientOptions: ClientOptions
  ): Resource[F, Service[F, A]] =
    mkClientResource[F, A](channel, (a: A) => mkMetadata(a).pure[F], clientOptions)

  final def clientResource[F[_]: Async, A](
      channel: Channel,
      mkMetadata: A => Metadata
  ): Resource[F, Service[F, A]] =
    mkClientResource[F, A](channel, (a: A) => mkMetadata(a).pure[F], ClientOptions.default)

  final def stub[F[_]: Async](
      dispatcher: Dispatcher[F],
      channel: Channel,
      clientOptions: ClientOptions
  ): Service[F, Metadata] =
    mkClient[F, Metadata](dispatcher, channel, (m: Metadata) => m.pure[F], clientOptions)

  final def stub[F[_]: Async](
      dispatcher: Dispatcher[F],
      channel: Channel
  ): Service[F, Metadata] =
    mkClient[F, Metadata](dispatcher, channel, (m: Metadata) => m.pure[F], ClientOptions.default)

  final def stubResource[F[_]: Async](
      channel: Channel,
      clientOptions: ClientOptions
  ): Resource[F, Service[F, Metadata]] =
    mkClientResource[F, Metadata](channel, (m: Metadata) => m.pure[F], clientOptions)

  final def stubResource[F[_]: Async](
      channel: Channel
  ): Resource[F, Service[F, Metadata]] =
    mkClientResource[F, Metadata](channel, (m: Metadata) => m.pure[F], ClientOptions.default)

///=== Service =========================================================================================================

  protected def serviceBindingFull[F[_], G[_]: Async, A](
      dispatcher: Dispatcher[G],
      serviceImpl: Service[F, A],
      serviceAspect: ServiceAspect[F, G, A],
      serverOptions: ServerOptions
  ): ServerServiceDefinition

  protected def serviceBinding[F[_]: Async, A](
      dispatcher: Dispatcher[F],
      serviceImpl: Service[F, A],
      mkCtx: Metadata => F[A],
      serverOptions: ServerOptions
  ): ServerServiceDefinition =
    serviceBindingFull[F, F, A](
      dispatcher,
      serviceImpl,
      ServiceAspect.default[F].modify(mkCtx),
      serverOptions
    )

  final def serviceFull[F[_], G[_]: Async, A](
      dispatcher: Dispatcher[G],
      serviceImpl: Service[F, A],
      serviceAspect: ServiceAspect[F, G, A],
      serverOptions: ServerOptions
  ): ServerServiceDefinition =
    serviceBindingFull[F, G, A](dispatcher, serviceImpl, serviceAspect, serverOptions)

  final def service[F[_]: Async, A](
      dispatcher: Dispatcher[F],
      serviceImpl: Service[F, A],
      f: Metadata => F[A],
      serverOptions: ServerOptions
  ): ServerServiceDefinition = {

    val mkCtx: Metadata => F[A] = f(_).handleErrorWith {
      case e: StatusException => e.raiseError[F, A]
      case e: StatusRuntimeException => e.raiseError[F, A]
      case e: Throwable => Status.INTERNAL.withDescription(e.getMessage).asRuntimeException().raiseError[F, A]
    }

    serviceBinding[F, A](dispatcher, serviceImpl, mkCtx, serverOptions)
  }

  final def service[F[_]: Async, A](
      dispatcher: Dispatcher[F],
      serviceImpl: Service[F, A],
      f: Metadata => F[A]
  ): ServerServiceDefinition =
    service[F, A](dispatcher, serviceImpl, f, ServerOptions.default)

  final def serviceResource[F[_]: Async, A](
      serviceImpl: Service[F, A],
      f: Metadata => F[A],
      serverOptions: ServerOptions
  ): Resource[F, ServerServiceDefinition] =
    Dispatcher.parallel[F].map(service[F, A](_, serviceImpl, f, serverOptions))

  final def serviceResource[F[_]: Async, A](
      serviceImpl: Service[F, A],
      f: Metadata => F[A]
  ): Resource[F, ServerServiceDefinition] =
    serviceResource[F, A](serviceImpl, f, ServerOptions.default)

  final def bindService[F[_]: Async](
      dispatcher: Dispatcher[F],
      serviceImpl: Service[F, Metadata],
      serverOptions: ServerOptions
  ): ServerServiceDefinition =
    service[F, Metadata](dispatcher, serviceImpl, (md: Metadata) => md.pure[F], serverOptions)

  final def bindService[F[_]: Async](
      dispatcher: Dispatcher[F],
      serviceImpl: Service[F, Metadata]
  ): ServerServiceDefinition =
    bindService[F](dispatcher, serviceImpl, ServerOptions.default)

  final def bindServiceResource[F[_]: Async](
      serviceImpl: Service[F, Metadata],
      serverOptions: ServerOptions
  ): Resource[F, ServerServiceDefinition] =
    serviceResource[F, Metadata](serviceImpl, (md: Metadata) => md.pure[F], serverOptions)

  final def bindServiceResource[F[_]: Async](
      serviceImpl: Service[F, Metadata]
  ): Resource[F, ServerServiceDefinition] =
    bindServiceResource[F](serviceImpl, ServerOptions.default)

///=====================================================================================================================

}
