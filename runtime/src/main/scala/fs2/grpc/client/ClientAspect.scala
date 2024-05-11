package fs2.grpc.client

import io.grpc._
import cats._
import cats.syntax.all._
import fs2.Stream

final case class ClientCallContext[Req, Res, A](
    ctx: A,
    methodDescriptor: MethodDescriptor[Req, Res]
)

trait ClientAspect[F[_], G[_], A] { self =>
  def visitUnaryToUnary[Req, Res](
      callCtx: ClientCallContext[Req, Res, A],
      req: Req,
      request: (Req, Metadata) => G[Res]
  ): F[Res]

  def visitUnaryToStreaming[Req, Res](
      callCtx: ClientCallContext[Req, Res, A],
      req: Req,
      request: (Req, Metadata) => Stream[G, Res]
  ): Stream[F, Res]

  def visitStreamingToUnary[Req, Res](
      callCtx: ClientCallContext[Req, Res, A],
      req: Stream[F, Req],
      request: (Stream[G, Req], Metadata) => G[Res]
  ): F[Res]

  def visitStreamingToStreaming[Req, Res](
      callCtx: ClientCallContext[Req, Res, A],
      req: Stream[F, Req],
      request: (Stream[G, Req], Metadata) => Stream[G, Res]
  ): Stream[F, Res]

  def contraModify[B](f: B => F[A])(implicit F: Monad[F]): ClientAspect[F, G, B] =
    new ClientAspect[F, G, B] {
      def modCtx[Req, Res](ccc: ClientCallContext[Req, Res, B]): F[ClientCallContext[Req, Res, A]] =
        f(ccc.ctx).map(a => ccc.copy(ctx = a))

      override def visitUnaryToUnary[Req, Res](
          callCtx: ClientCallContext[Req, Res, B],
          req: Req,
          request: (Req, Metadata) => G[Res]
      ): F[Res] =
        modCtx(callCtx).flatMap(self.visitUnaryToUnary(_, req, request))

      override def visitUnaryToStreaming[Req, Res](
          callCtx: ClientCallContext[Req, Res, B],
          req: Req,
          request: (Req, Metadata) => Stream[G, Res]
      ): Stream[F, Res] =
        Stream.eval(modCtx(callCtx)).flatMap(self.visitUnaryToStreaming(_, req, request))

      override def visitStreamingToUnary[Req, Res](
          callCtx: ClientCallContext[Req, Res, B],
          req: Stream[F, Req],
          request: (Stream[G, Req], Metadata) => G[Res]
      ): F[Res] =
        modCtx(callCtx).flatMap(self.visitStreamingToUnary(_, req, request))

      override def visitStreamingToStreaming[Req, Res](
          callCtx: ClientCallContext[Req, Res, B],
          req: Stream[F, Req],
          request: (Stream[G, Req], Metadata) => Stream[G, Res]
      ): Stream[F, Res] =
        Stream.eval(modCtx(callCtx)).flatMap(self.visitStreamingToStreaming(_, req, request))
    }
}

object ClientAspect {
  def default[F[_]] = new ClientAspect[F, F, Metadata] {
    override def visitUnaryToUnary[Req, Res](
        callCtx: ClientCallContext[Req, Res, Metadata],
        req: Req,
        request: (Req, Metadata) => F[Res]
    ): F[Res] = request(req, callCtx.ctx)

    override def visitUnaryToStreaming[Req, Res](
        callCtx: ClientCallContext[Req, Res, Metadata],
        req: Req,
        request: (Req, Metadata) => Stream[F, Res]
    ): Stream[F, Res] = request(req, callCtx.ctx)

    override def visitStreamingToUnary[Req, Res](
        callCtx: ClientCallContext[Req, Res, Metadata],
        req: Stream[F, Req],
        request: (Stream[F, Req], Metadata) => F[Res]
    ): F[Res] = request(req, callCtx.ctx)

    override def visitStreamingToStreaming[Req, Res](
        callCtx: ClientCallContext[Req, Res, Metadata],
        req: Stream[F, Req],
        request: (Stream[F, Req], Metadata) => Stream[F, Res]
    ): Stream[F, Res] = request(req, callCtx.ctx)
  }
}
