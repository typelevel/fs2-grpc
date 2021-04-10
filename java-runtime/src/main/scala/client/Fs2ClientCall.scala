package org.lyranthe.fs2_grpc
package java_runtime
package client

import cats.effect._
import cats.implicits._
import io.grpc.{Metadata, _}
import fs2._

final case class UnaryResult[A](value: Option[A], status: Option[GrpcStatus])
final case class GrpcStatus(status: Status, trailers: Metadata)

class Fs2ClientCall[F[_], Request, Response] private[client] (
    call: ClientCall[Request, Response],
    errorAdapter: StatusRuntimeException => Option[Exception]
) {

  private val ea: PartialFunction[Throwable, Throwable] = { case e: StatusRuntimeException =>
    errorAdapter(e).getOrElse(e)
  }

  private def cancel(message: Option[String], cause: Option[Throwable])(implicit F: Sync[F]): F[Unit] =
    F.delay(call.cancel(message.orNull, cause.orNull))

  private def halfClose(implicit F: Sync[F]): F[Unit] =
    F.delay(call.halfClose())

  private def request(numMessages: Int)(implicit F: Sync[F]): F[Unit] =
    F.delay(call.request(numMessages))

  private def sendMessage(message: Request)(implicit F: Sync[F]): F[Unit] =
    F.delay(call.sendMessage(message))

  private def start(listener: ClientCall.Listener[Response], metadata: Metadata)(implicit F: Sync[F]): F[Unit] =
    F.delay(call.start(listener, metadata))

  def startListener[A <: ClientCall.Listener[Response]](createListener: F[A], headers: Metadata)(implicit
      F: Sync[F]
  ): F[A] =
    createListener.flatTap(start(_, headers)) <* request(1)

  def sendSingleMessage(message: Request)(implicit F: Sync[F]): F[Unit] =
    sendMessage(message) *> halfClose

  def sendStream(stream: Stream[F, Request])(implicit F: Sync[F]): Stream[F, Unit] =
    stream.evalMap(sendMessage) ++ Stream.eval(halfClose)

  private def handleExitCase(
      cancelComplete: Boolean
  )(implicit F: Sync[F]): (ClientCall.Listener[Response], ExitCase[Throwable]) => F[Unit] = {
    case (_, ExitCase.Completed) => cancel("call done".some, None).whenA(cancelComplete)
    case (_, ExitCase.Canceled) => cancel("call was cancelled".some, None)
    case (_, ExitCase.Error(t)) => cancel(t.getMessage.some, t.some)
  }

  def unaryToUnaryCall(message: Request, headers: Metadata)(implicit F: ConcurrentEffect[F]): F[Response] =
    F.bracketCase(startListener(Fs2UnaryClientCallListener[F, Response], headers))(l =>
      sendSingleMessage(message) *> l.getValue.adaptError(ea)
    )(handleExitCase(cancelComplete = false))

  def streamingToUnaryCall(messages: Stream[F, Request], headers: Metadata)(implicit
      F: ConcurrentEffect[F]
  ): F[Response] =
    F.bracketCase(startListener(Fs2UnaryClientCallListener[F, Response], headers))(l =>
      Stream.eval(l.getValue.adaptError(ea)).concurrently(sendStream(messages)).compile.lastOrError
    )(handleExitCase(cancelComplete = false))

  def unaryToStreamingCall(message: Request, headers: Metadata)(implicit F: ConcurrentEffect[F]): Stream[F, Response] =
    Stream
      .bracketCase(startListener(Fs2StreamClientCallListener[F, Response](call.request), headers))(
        handleExitCase(cancelComplete = true)
      )
      .flatMap(Stream.eval_(sendSingleMessage(message)) ++ _.stream.adaptError(ea))

  def streamingToStreamingCall(messages: Stream[F, Request], headers: Metadata)(implicit
      F: ConcurrentEffect[F]
  ): Stream[F, Response] =
    Stream
      .bracketCase(startListener(Fs2StreamClientCallListener[F, Response](call.request), headers))(
        handleExitCase(cancelComplete = true)
      )
      .flatMap(_.stream.adaptError(ea).concurrently(sendStream(messages)))
}

object Fs2ClientCall {

  def apply[F[_]]: PartiallyAppliedClientCall[F] = new PartiallyAppliedClientCall[F]

  class PartiallyAppliedClientCall[F[_]](val dummy: Boolean = false) extends AnyVal {

    def apply[Request, Response](
        channel: Channel,
        methodDescriptor: MethodDescriptor[Request, Response],
        callOptions: CallOptions,
        errorAdapter: StatusRuntimeException => Option[Exception] = _ => None
    )(implicit F: Sync[F]): F[Fs2ClientCall[F, Request, Response]] = {
      F.delay(new Fs2ClientCall(channel.newCall[Request, Response](methodDescriptor, callOptions), errorAdapter))
    }

  }

}
