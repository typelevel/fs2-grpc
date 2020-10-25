package org
package lyranthe
package fs2_grpc
package java_runtime

trait GeneratedCompanion[Service[*[_], _]] {

  import cats.syntax.all._
  import cats.effect.{Async, Resource}
  import cats.effect.std.Dispatcher
  import io.grpc.{Channel, Metadata, CallOptions, StatusRuntimeException, ServerServiceDefinition}

///=====================================================================================================================

  def client[F[_]: Async, A](
      dispatcher: Dispatcher[F],
      channel: Channel,
      f: A => Metadata,
      coFn: CallOptions => CallOptions = identity,
      errorAdapter: StatusRuntimeException => Option[Exception] = _ => None
  ): Service[F, A]

  final def clientResource[F[_]: Async, A](
      channel: Channel,
      f: A => Metadata,
      coFn: CallOptions => _root_.io.grpc.CallOptions = identity,
      errorAdapter: StatusRuntimeException => Option[Exception] = _ => None
  ): Resource[F, Service[F, A]] =
    Dispatcher[F].map(client[F, A](_, channel, f, coFn, errorAdapter))

  final def stub[F[_]: Async](
      dispatcher: Dispatcher[F],
      channel: Channel,
      callOptions: CallOptions = CallOptions.DEFAULT,
      errorAdapter: StatusRuntimeException => Option[Exception] = _ => None
  ): Service[F, Metadata] =
    client[F, Metadata](dispatcher, channel, identity, _ => callOptions, errorAdapter)

  final def stubResource[F[_]: Async](
      channel: Channel,
      callOptions: CallOptions = CallOptions.DEFAULT,
      errorAdapter: StatusRuntimeException => Option[Exception] = _ => None
  ): Resource[F, Service[F, Metadata]] =
    clientResource[F, Metadata](channel, identity, _ => callOptions, errorAdapter)

///=====================================================================================================================

  def service[F[_]: Async, A](
      dispatcher: Dispatcher[F],
      serviceImpl: Service[F, A],
      f: Metadata => Either[String, A]
  ): ServerServiceDefinition

  final def serviceResource[F[_]: Async, A](
      serviceImpl: Service[F, A],
      f: Metadata => Either[String, A]
  ): Resource[F, ServerServiceDefinition] =
    Dispatcher[F].map(service[F, A](_, serviceImpl, f))

  final def bindServiceResource[F[_]: Async](
      serviceImpl: Service[F, Metadata]
  ): Resource[F, ServerServiceDefinition] =
    serviceResource[F, Metadata](serviceImpl, _.asRight[String])

  final def bindService[F[_]: Async](
      dispatcher: Dispatcher[F],
      serviceImpl: Service[F, Metadata]
  ): ServerServiceDefinition =
    service[F, Metadata](dispatcher, serviceImpl, _.asRight[String])

///=====================================================================================================================

}
