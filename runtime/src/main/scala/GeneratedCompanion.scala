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

trait GeneratedCompanion[Service[*[_], _]] {

///=== Client ==========================================================================================================

  def client[F[_]: Async, A](
      dispatcher: Dispatcher[F],
      channel: Channel,
      mkMetadata: A => Metadata,
      clientOptions: ClientOptions
  ): Service[F, A]

  def clientResource[F[_]: Async, A](
      channel: Channel,
      mkMetadata: A => Metadata,
      clientOptions: ClientOptions
  ): Resource[F, Service[F, A]] =
    Dispatcher[F].map(client[F, A](_, channel, mkMetadata, clientOptions))

  @deprecated("Will be removed from public API - use alternative overload.", "1.2.0")
  def client[F[_]: Async, A](
      dispatcher: Dispatcher[F],
      channel: Channel,
      f: A => Metadata,
      coFn: CallOptions => CallOptions = identity,
      errorAdapter: StatusRuntimeException => Option[Exception] = _ => None
  ): Service[F, A] =
    client[F, A](
      dispatcher,
      channel,
      f,
      ClientOptions.default
        .withCallOptionsFn(coFn)
        .withErrorAdapter(Function.unlift(errorAdapter))
    )

  @deprecated("Will be removed from public API - use alternative overload.", "1.2.0")
  final def clientResource[F[_]: Async, A](
      channel: Channel,
      f: A => Metadata,
      coFn: CallOptions => _root_.io.grpc.CallOptions = identity,
      errorAdapter: StatusRuntimeException => Option[Exception] = _ => None
  ): Resource[F, Service[F, A]] =
    clientResource[F, A](
      channel,
      f,
      ClientOptions.default
        .withCallOptionsFn(coFn)
        .withErrorAdapter(Function.unlift(errorAdapter))
    )

  final def stub[F[_]: Async](
      dispatcher: Dispatcher[F],
      channel: Channel,
      clientOptions: ClientOptions
  ): Service[F, Metadata] =
    client[F, Metadata](dispatcher, channel, (m: Metadata) => m, clientOptions)

  @deprecated("Will be removed from public API - use alternative overload.", "1.2.0")
  final def stub[F[_]: Async](
      dispatcher: Dispatcher[F],
      channel: Channel,
      callOptions: CallOptions = CallOptions.DEFAULT,
      errorAdapter: StatusRuntimeException => Option[Exception] = _ => None
  ): Service[F, Metadata] =
    stub[F](
      dispatcher,
      channel,
      ClientOptions.default
        .withCallOptionsFn((_: CallOptions) => callOptions)
        .withErrorAdapter(Function.unlift(errorAdapter))
    )

  final def stubResource[F[_]: Async](
      channel: Channel,
      clientOptions: ClientOptions
  ): Resource[F, Service[F, Metadata]] =
    clientResource[F, Metadata](channel, (m: Metadata) => m, clientOptions)

  @deprecated("Will be removed from public API - use alternative overload.", "1.2.0")
  final def stubResource[F[_]: Async](
      channel: Channel,
      callOptions: CallOptions = CallOptions.DEFAULT,
      errorAdapter: StatusRuntimeException => Option[Exception] = _ => None
  ): Resource[F, Service[F, Metadata]] =
    clientResource[F, Metadata](channel, (m: Metadata) => m, (_: CallOptions) => callOptions, errorAdapter)

///=== Service =========================================================================================================

  protected def serviceBinding[F[_]: Async, A](
      dispatcher: Dispatcher[F],
      serviceImpl: Service[F, A],
      mkCtx: Metadata => F[A]
  ): ServerServiceDefinition

  final def service[F[_]: Async, A](
      dispatcher: Dispatcher[F],
      serviceImpl: Service[F, A],
      f: Metadata => F[A]
  ): ServerServiceDefinition = {

    val mkCtx: Metadata => F[A] = f(_).handleErrorWith {
      case e: StatusException => e.raiseError[F, A]
      case e: StatusRuntimeException => e.raiseError[F, A]
      case e: Throwable => Status.INTERNAL.withDescription(e.getMessage).asRuntimeException().raiseError[F, A]
    }

    serviceBinding[F, A](dispatcher, serviceImpl, mkCtx)
  }

  final def serviceResource[F[_]: Async, A](
      serviceImpl: Service[F, A],
      f: Metadata => F[A]
  ): Resource[F, ServerServiceDefinition] =
    Dispatcher[F].map(service[F, A](_, serviceImpl, f))

  final def bindService[F[_]: Async](
      dispatcher: Dispatcher[F],
      serviceImpl: Service[F, Metadata]
  ): ServerServiceDefinition =
    service[F, Metadata](dispatcher, serviceImpl, _.pure[F])

  final def bindServiceResource[F[_]: Async](
      serviceImpl: Service[F, Metadata]
  ): Resource[F, ServerServiceDefinition] =
    serviceResource[F, Metadata](serviceImpl, _.pure[F])

///=====================================================================================================================

}
