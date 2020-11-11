package org.lyranthe.fs2_grpc
package java_runtime
package client

import cats.syntax.all._
import cats.effect.{Async, Resource}
import cats.effect.std.Dispatcher
import io.grpc.{Metadata, _}
import fs2._

final case class UnaryResult[A](value: Option[A], status: Option[GrpcStatus])
final case class GrpcStatus(status: Status, trailers: Metadata)

class Fs2ClientCall[F[_], Request, Response] private[client] (
    call: ClientCall[Request, Response],
    dispatcher: Dispatcher[F],
    errorAdapter: StatusRuntimeException => Option[Exception]
)(implicit F: Async[F]) {

  private val ea: PartialFunction[Throwable, Throwable] = { case e: StatusRuntimeException =>
    errorAdapter(e).getOrElse(e)
  }

  private def cancel(message: Option[String], cause: Option[Throwable]): F[Unit] =
    F.delay(call.cancel(message.orNull, cause.orNull))

  private val halfClose: F[Unit] =
    F.delay(call.halfClose())

  private val requestOne: F[Unit] =
    F.delay(call.request(1))

  private def sendMessage(message: Request): F[Unit] =
    F.delay(call.sendMessage(message))

  private def start(listener: ClientCall.Listener[Response], md: Metadata): F[Unit] =
    F.delay(call.start(listener, md))

  private def startListener[A <: ClientCall.Listener[Response]](createListener: F[A], md: Metadata): F[A] =
    createListener.flatTap(start(_, md)) <* requestOne

  private def sendSingleMessage(message: Request): F[Unit] =
    sendMessage(message) *> halfClose

  private def sendStream(stream: Stream[F, Request]): Stream[F, Unit] =
    stream.evalMap(sendMessage) ++ Stream.eval(halfClose)

  ///

  def unaryToUnaryCall(message: Request, headers: Metadata): F[Response] =
    Stream
      .resource(mkClientListenerR(headers))
      .evalMap(sendSingleMessage(message) *> _.getValue.adaptError(ea))
      .compile
      .lastOrError

  def streamingToUnaryCall(messages: Stream[F, Request], headers: Metadata): F[Response] =
    Stream
      .resource(mkClientListenerR(headers))
      .flatMap(l => Stream.eval(l.getValue.adaptError(ea)).concurrently(sendStream(messages)))
      .compile
      .lastOrError

  def unaryToStreamingCall(message: Request, md: Metadata): Stream[F, Response] =
    Stream
      .resource(mkStreamListenerR(md))
      .flatMap(Stream.exec(sendSingleMessage(message)) ++ _.stream.adaptError(ea))

  def streamingToStreamingCall(messages: Stream[F, Request], md: Metadata): Stream[F, Response] =
    Stream
      .resource(mkStreamListenerR(md))
      .flatMap(_.stream.adaptError(ea).concurrently(sendStream(messages)))

  ///

  private def handleCallError: (ClientCall.Listener[Response], Resource.ExitCase) => F[Unit] = {
    case (_, Resource.ExitCase.Succeeded) => F.unit
    case (_, Resource.ExitCase.Canceled) => cancel("call was cancelled".some, None)
    case (_, Resource.ExitCase.Errored(t)) => cancel(t.getMessage.some, t.some)
  }

  private def mkClientListenerR(md: Metadata): Resource[F, Fs2UnaryClientCallListener[F, Response]] =
    Resource.makeCase(
      startListener(Fs2UnaryClientCallListener[F, Response](dispatcher), md)
    )(handleCallError)

  private def mkStreamListenerR(md: Metadata): Resource[F, Fs2StreamClientCallListener[F, Response]] =
    Resource.makeCase(
      startListener(Fs2StreamClientCallListener[F, Response](call.request(_), dispatcher), md)
    )(handleCallError)

}

object Fs2ClientCall {

  def apply[F[_]]: PartiallyAppliedClientCall[F] = new PartiallyAppliedClientCall[F]

  class PartiallyAppliedClientCall[F[_]](val dummy: Boolean = false) extends AnyVal {

    def apply[Request, Response](
        channel: Channel,
        methodDescriptor: MethodDescriptor[Request, Response],
        callOptions: CallOptions,
        dispatcher: Dispatcher[F],
        errorAdapter: StatusRuntimeException => Option[Exception]
    )(implicit F: Async[F]): F[Fs2ClientCall[F, Request, Response]] = {
      F.delay(
        new Fs2ClientCall(
          channel.newCall[Request, Response](methodDescriptor, callOptions),
          dispatcher,
          errorAdapter
        )
      )
    }

  }

}
