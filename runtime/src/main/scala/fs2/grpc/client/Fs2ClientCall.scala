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

import cats.effect.std.Dispatcher
import cats.effect.{Async, Resource, SyncIO}
import cats.syntax.all._
import fs2.grpc.client.internal.Fs2UnaryCallHandler
import fs2.grpc.shared.StreamOutput
import io.grpc._

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

  private def start[A <: ClientCall.Listener[Response]](createListener: F[A], md: Metadata): F[A] =
    createListener.flatTap(l => F.delay(call.start(l, md)))

  private def sendSingleMessage(message: Request): F[Unit] =
    F.delay(call.sendMessage(message)) *> halfClose

  //

  def unaryToUnaryCall(message: Request, headers: Metadata): F[Response] =
    Fs2UnaryCallHandler.unary(call, options, message, headers).map(_._1)

  def unaryToUnaryCallTrailers(message: Request, headers: Metadata): F[(Response, Metadata)] =
    Fs2UnaryCallHandler.unary(call, options, message, headers)

  def streamingToUnaryCallTrailers(messages: Stream[F, Request], headers: Metadata): F[(Response, Metadata)] =
    StreamOutput.client(call).flatMap { output =>
      Fs2UnaryCallHandler.stream(call, options, dispatcher, messages, output, headers)
    }

  def streamingToUnaryCall(messages: Stream[F, Request], headers: Metadata): F[Response] =
    StreamOutput.client(call).flatMap { output =>
      Fs2UnaryCallHandler.stream(call, options, dispatcher, messages, output, headers).map(_._1)
    }

  def unaryToStreamingCall(message: Request, md: Metadata): Stream[F, Response] =
    Stream
      .resource(mkStreamListenerR(md, SyncIO.unit))
      .flatMap(Stream.exec(sendSingleMessage(message)) ++ _.stream.adaptError(ea))

  def streamingToStreamingCall(messages: Stream[F, Request], md: Metadata): Stream[F, Response] = {
    val listenerAndOutput = Resource.eval(StreamOutput.client(call)).flatMap { output =>
      mkStreamListenerR(md, output.onReadySync(dispatcher)).map((_, output))
    }

    Stream
      .resource(listenerAndOutput)
      .flatMap { case (listener, output) =>
        listener.stream
          .adaptError(ea)
          .concurrently(output.writeStream(messages) ++ Stream.eval(halfClose))
      }
  }

  //

  private def handleExitCase(cancelSucceed: Boolean): (ClientCall.Listener[Response], Resource.ExitCase) => F[Unit] = {
    case (_, Resource.ExitCase.Succeeded) => cancel("call done".some, None).whenA(cancelSucceed)
    case (_, Resource.ExitCase.Canceled) => cancel("call was cancelled".some, None)
    case (_, Resource.ExitCase.Errored(t)) => cancel(t.getMessage.some, t.some)
  }

  private def mkStreamListenerR(
      md: Metadata,
      signalReadiness: SyncIO[Unit]
  ): Resource[F, Fs2StreamClientCallListener[F, Response]] = {
    val prefetchN = options.prefetchN.max(1)
    val create = Fs2StreamClientCallListener.create[F, Response](request, signalReadiness, dispatcher, prefetchN)
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
