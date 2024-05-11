package fs2.grpc.server

import io.grpc._
import cats._
import cats.syntax.all._
import fs2.Stream

final case class ServerCallContext[Req, Res](
    metadata: Metadata,
    methodDescriptor: MethodDescriptor[Req, Res]
)

trait ServiceAspect[F[_], G[_], A] { self =>
  def visitUnaryToUnary[Req, Res](
      callCtx: ServerCallContext[Req, Res],
      req: Req,
      next: (Req, A) => F[Res]
  ): G[Res]

  def visitUnaryToStreaming[Req, Res](
      callCtx: ServerCallContext[Req, Res],
      req: Req,
      next: (Req, A) => fs2.Stream[F, Res]
  ): fs2.Stream[G, Res]

  def visitStreamingToUnary[Req, Res](
      callCtx: ServerCallContext[Req, Res],
      req: fs2.Stream[G, Req],
      next: (fs2.Stream[F, Req], A) => F[Res]
  ): G[Res]

  def visitStreamingToStreaming[Req, Res](
      callCtx: ServerCallContext[Req, Res],
      req: fs2.Stream[G, Req],
      next: (fs2.Stream[F, Req], A) => fs2.Stream[F, Res]
  ): fs2.Stream[G, Res]

  def modify[B](f: A => F[B])(implicit F: Monad[F]): ServiceAspect[F, G, B] =
    new ServiceAspect[F, G, B] {
      override def visitUnaryToUnary[Req, Res](
          callCtx: ServerCallContext[Req, Res],
          req: Req,
          request: (Req, B) => F[Res]
      ): G[Res] =
        self.visitUnaryToUnary[Req, Res](
          callCtx,
          req,
          (req, a) => f(a).flatMap(request(req, _))
        )

      override def visitUnaryToStreaming[Req, Res](
          callCtx: ServerCallContext[Req, Res],
          req: Req,
          request: (Req, B) => Stream[F, Res]
      ): Stream[G, Res] =
        self.visitUnaryToStreaming[Req, Res](
          callCtx,
          req,
          (req, a) => fs2.Stream.eval(f(a)).flatMap(request(req, _))
        )

      override def visitStreamingToUnary[Req, Res](
          callCtx: ServerCallContext[Req, Res],
          req: fs2.Stream[G, Req],
          request: (Stream[F, Req], B) => F[Res]
      ): G[Res] =
        self.visitStreamingToUnary[Req, Res](
          callCtx,
          req,
          (req, a) => f(a).flatMap(request(req, _))
        )

      override def visitStreamingToStreaming[Req, Res](
          callCtx: ServerCallContext[Req, Res],
          req: fs2.Stream[G, Req],
          request: (Stream[F, Req], B) => Stream[F, Res]
      ): Stream[G, Res] =
        self.visitStreamingToStreaming[Req, Res](
          callCtx,
          req,
          (req, a) => fs2.Stream.eval(f(a)).flatMap(request(req, _))
        )
    }
}

object ServiceAspect {
  def default[F[_]] = new ServiceAspect[F, F, Metadata] {
    override def visitUnaryToUnary[Req, Res](
        callCtx: ServerCallContext[Req, Res],
        req: Req,
        request: (Req, Metadata) => F[Res]
    ): F[Res] = request(req, callCtx.metadata)

    override def visitUnaryToStreaming[Req, Res](
        callCtx: ServerCallContext[Req, Res],
        req: Req,
        request: (Req, Metadata) => Stream[F, Res]
    ): Stream[F, Res] = request(req, callCtx.metadata)

    override def visitStreamingToUnary[Req, Res](
        callCtx: ServerCallContext[Req, Res],
        req: fs2.Stream[F, Req],
        request: (Stream[F, Req], Metadata) => F[Res]
    ): F[Res] = request(req, callCtx.metadata)

    override def visitStreamingToStreaming[Req, Res](
        callCtx: ServerCallContext[Req, Res],
        req: fs2.Stream[F, Req],
        request: (Stream[F, Req], Metadata) => Stream[F, Res]
    ): Stream[F, Res] = request(req, callCtx.metadata)
  }
}
