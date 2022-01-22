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
package client

import cats.syntax.all._
import cats.effect.{Async, Resource}
import cats.effect.std.Dispatcher
import io.grpc.{Metadata, _}

final case class UnaryResult[A](value: Option[A], status: Option[GrpcStatus])
final case class GrpcStatus(status: Status, trailers: Metadata)

class Fs2ClientCall[F[_], Request, Response] private[client] (
    call: ClientCall[Request, Response],
    dispatcher: Dispatcher[F],
    options: ClientOptions
)(implicit F: Async[F]) {

  private val ea: PartialFunction[Throwable, Throwable] = { case e: StatusRuntimeException =>
    options.errorAdapter.lift(e).getOrElse(e)
  }

  private def cancel(message: Option[String], cause: Option[Throwable]): F[Unit] =
    F.delay(call.cancel(message.orNull, cause.orNull))

  private val halfClose: F[Unit] =
    F.delay(call.halfClose())

  private def request(numMessages: Int): F[Unit] =
    F.delay(call.request(numMessages))

  private def sendMessage(message: Request): F[Unit] =
    F.delay(call.sendMessage(message))

  private def start[A <: ClientCall.Listener[Response]](createListener: F[A], md: Metadata): F[A] =
    createListener.flatTap(l => F.delay(call.start(l, md)))

  private def sendSingleMessage(message: Request): F[Unit] =
    sendMessage(message) *> halfClose

  private def sendStream(stream: Stream[F, Request]): Stream[F, Unit] =
    stream.evalMap(sendMessage) ++ Stream.eval(halfClose)

  //

  def unaryToUnaryCall(message: Request, headers: Metadata): F[Response] =
    mkUnaryListenerR(headers)
      .use(sendSingleMessage(message) *> _.getValue.adaptError(ea))

  def streamingToUnaryCall(messages: Stream[F, Request], headers: Metadata): F[Response] =
    Stream
      .resource(mkUnaryListenerR(headers))
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

  //

  private def handleExitCase(cancelSucceed: Boolean): (ClientCall.Listener[Response], Resource.ExitCase) => F[Unit] = {
    case (_, Resource.ExitCase.Succeeded) => cancel("call done".some, None).whenA(cancelSucceed)
    case (_, Resource.ExitCase.Canceled) => cancel("call was cancelled".some, None)
    case (_, Resource.ExitCase.Errored(t)) => cancel(t.getMessage.some, t.some)
  }

  private def mkUnaryListenerR(md: Metadata): Resource[F, Fs2UnaryClientCallListener[F, Response]] = {

    val create = Fs2UnaryClientCallListener.create[F, Response](dispatcher)
    val acquire = start(create, md) <* request(1)
    val release = handleExitCase(cancelSucceed = false)

    Resource.makeCase(acquire)(release)
  }

  private def mkStreamListenerR(md: Metadata): Resource[F, Fs2StreamClientCallListener[F, Response]] = {

    val prefetchN = options.prefetchN.max(1)
    val create = Fs2StreamClientCallListener.create[F, Response](request, dispatcher, prefetchN)
    val acquire = start(create, md) <* request(prefetchN)
    val release = handleExitCase(cancelSucceed = true)

    Resource.makeCase(acquire)(release)
  }
}

object Fs2ClientCall {

  def apply[F[_]]: PartiallyAppliedClientCall[F] = new PartiallyAppliedClientCall[F]

  class PartiallyAppliedClientCall[F[_]](val dummy: Boolean = false) extends AnyVal {

    def apply[Request, Response](
        channel: Channel,
        methodDescriptor: MethodDescriptor[Request, Response],
        dispatcher: Dispatcher[F],
        clientOptions: ClientOptions
    )(implicit F: Async[F]): F[Fs2ClientCall[F, Request, Response]] = {
      F.delay(
        new Fs2ClientCall(
          channel.newCall[Request, Response](methodDescriptor, clientOptions.callOptionsFn(CallOptions.DEFAULT)),
          dispatcher,
          clientOptions
        )
      )
    }

  }

}
