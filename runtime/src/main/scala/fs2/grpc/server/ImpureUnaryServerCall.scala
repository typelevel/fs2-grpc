package fs2.grpc.server

import cats.effect.Async
import cats.effect.Sync
import cats.effect.kernel.Outcome
import cats.effect.std.Dispatcher
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException


object ImpureUnaryServerCall {
  private val Noop: () => Any = () => ()

  private def tooManyRequests =
    Status.INTERNAL.withDescription("Too many requests").asRuntimeException

  def mkListener[F[_] : Sync, Request, Response](
    thunk: Request => F[Unit],
    dispatcher: Dispatcher[F],
    call: ServerCall[Request, Response],
  ): ServerCall.Listener[Request] =
    new ServerCall.Listener[Request] {

      private var request: Request = _
      private var cancel: () => Any = Noop
      private var completed = false

      override def onCancel(): Unit =
        cancel()

      override def onMessage(message: Request): Unit =
        if (request == null) {
          request = message
        } else {
          if (!completed) {
            completed = true
            call.close(Status.INTERNAL.withDescription("Too many requests"), new Metadata())
          }
        }

      override def onHalfClose(): Unit =
        if (!completed) {
          if (request == null) {
            completed = true
            call.close(Status.INTERNAL.withDescription("Half-closed without a request"), new Metadata())
          } else {
            cancel = dispatcher.unsafeRunCancelable(thunk(request))
          }
        }
    }

  def unary[F[_] : Async, Request, Response](
    impl: (Request, Metadata) => F[Response],
    options: ServerOptions,
    dispatcher: Dispatcher[F],
  ): ServerCallHandler[Request, Response] =
    new ServerCallHandler[Request, Response] {
      def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        val responder = ImpureResponder.setup(options, call)
        call.request(2)
        mkListener[F, Request, Response](req => responder.unary(impl(req, headers)), dispatcher, call)
      }
    }

  def stream[F[_] : Async, Request, Response](
    impl: (Request, Metadata) => fs2.Stream[F, Response],
    options: ServerOptions,
    dispatcher: Dispatcher[F],
  ): ServerCallHandler[Request, Response] =
    new ServerCallHandler[Request, Response] {
      def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        val responder = ImpureResponder.setup(options, call)
        call.request(2)
        mkListener[F, Request, Response](req => responder.stream(impl(req, headers)), dispatcher, call)
      }
    }
}

final class ImpureResponder[Request, Response](val call: ServerCall[Request, Response]) {

  def stream[F[_] : Async](response: fs2.Stream[F, Response]): F[Unit] =
    guaranteeClose(response.map(sendMessage).compile.drain)

  def unary[F[_]](response: F[Response])(implicit F: Sync[F]): F[Unit] =
    guaranteeClose(F.map(response)(sendMessage))

  private var sentHeader: Boolean = false

  private def sendMessage(message: Response): Unit =
    if (!sentHeader) {
      sentHeader = true
      call.sendHeaders(new Metadata())
      call.sendMessage(message)
    } else {
      call.sendMessage(message)
    }

  private def guaranteeClose[F[_]](completed: F[Unit])(implicit F: Sync[F]): F[Unit] = {
    F.guaranteeCase(completed) {
      case Outcome.Succeeded(_) => closeStream(Status.OK, new Metadata())
      case Outcome.Errored(e) =>
        e match {
          case ex: StatusException =>
            closeStream(ex.getStatus, Option(ex.getTrailers).getOrElse(new Metadata()))
          case ex: StatusRuntimeException =>
            closeStream(ex.getStatus, Option(ex.getTrailers).getOrElse(new Metadata()))
          case ex =>
            closeStream(Status.INTERNAL.withDescription(ex.getMessage).withCause(ex), new Metadata())
        }
      case Outcome.Canceled() => closeStream(Status.CANCELLED, new Metadata())
    }
  }

  private def closeStream[F[_]](status: Status, metadata: Metadata)(implicit F: Sync[F]): F[Unit] =
    F.delay(call.close(status, metadata))
}

object ImpureResponder {
  def setup[I, O](options: ServerOptions, call: ServerCall[I, O]): ImpureResponder[I, O] = {
    val callOptions = options.callOptionsFn(ServerCallOptions.default)
    call.setMessageCompression(callOptions.messageCompression)
    callOptions.compressor.map(_.name).foreach(call.setCompression)
    new ImpureResponder[I, O](call)
  }
}
