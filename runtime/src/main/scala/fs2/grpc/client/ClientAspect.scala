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
  def visitUnaryToUnaryCall[Req, Res](
      callCtx: ClientCallContext[Req, Res, A],
      req: Req,
      run: (Req, Metadata) => G[Res]
  ): F[Res]

  def visitUnaryToStreamingCall[Req, Res](
      callCtx: ClientCallContext[Req, Res, A],
      req: Req,
      run: (Req, Metadata) => Stream[G, Res]
  ): Stream[F, Res]

  def visitStreamingToUnaryCall[Req, Res](
      callCtx: ClientCallContext[Req, Res, A],
      req: Stream[F, Req],
      run: (Stream[G, Req], Metadata) => G[Res]
  ): F[Res]

  def visitStreamingToStreamingCall[Req, Res](
      callCtx: ClientCallContext[Req, Res, A],
      req: Stream[F, Req],
      run: (Stream[G, Req], Metadata) => Stream[G, Res]
  ): Stream[F, Res]

  def visitUnaryToUnaryCallTrailers[Req, Res](
      callCtx: ClientCallContext[Req, Res, A],
      req: Req,
      run: (Req, Metadata) => G[(Res, Metadata)]
  ): F[(Res, Metadata)]

  def visitStreamingToUnaryCallTrailers[Req, Res](
      callCtx: ClientCallContext[Req, Res, A],
      req: Stream[F, Req],
      run: (Stream[G, Req], Metadata) => G[(Res, Metadata)]
  ): F[(Res, Metadata)]

  def contraModify[B](f: B => F[A])(implicit F: Monad[F]): ClientAspect[F, G, B] =
    new ClientAspect[F, G, B] {
      def modCtx[Req, Res](ccc: ClientCallContext[Req, Res, B]): F[ClientCallContext[Req, Res, A]] =
        f(ccc.ctx).map(a => ccc.copy(ctx = a))

      override def visitUnaryToUnaryCall[Req, Res](
          callCtx: ClientCallContext[Req, Res, B],
          req: Req,
          run: (Req, Metadata) => G[Res]
      ): F[Res] =
        modCtx(callCtx).flatMap(self.visitUnaryToUnaryCall(_, req, run))

      override def visitUnaryToStreamingCall[Req, Res](
          callCtx: ClientCallContext[Req, Res, B],
          req: Req,
          run: (Req, Metadata) => Stream[G, Res]
      ): Stream[F, Res] =
        Stream.eval(modCtx(callCtx)).flatMap(self.visitUnaryToStreamingCall(_, req, run))

      override def visitStreamingToUnaryCall[Req, Res](
          callCtx: ClientCallContext[Req, Res, B],
          req: Stream[F, Req],
          run: (Stream[G, Req], Metadata) => G[Res]
      ): F[Res] =
        modCtx(callCtx).flatMap(self.visitStreamingToUnaryCall(_, req, run))

      override def visitStreamingToStreamingCall[Req, Res](
          callCtx: ClientCallContext[Req, Res, B],
          req: Stream[F, Req],
          run: (Stream[G, Req], Metadata) => Stream[G, Res]
      ): Stream[F, Res] =
        Stream.eval(modCtx(callCtx)).flatMap(self.visitStreamingToStreamingCall(_, req, run))

      override def visitUnaryToUnaryCallTrailers[Req, Res](
          callCtx: ClientCallContext[Req, Res, B],
          req: Req,
          run: (Req, Metadata) => G[(Res, Metadata)]
      ): F[(Res, Metadata)] =
        modCtx(callCtx).flatMap(self.visitUnaryToUnaryCallTrailers(_, req, run))

      override def visitStreamingToUnaryCallTrailers[Req, Res](
          callCtx: ClientCallContext[Req, Res, B],
          req: Stream[F, Req],
          run: (Stream[G, Req], Metadata) => G[(Res, Metadata)]
      ): F[(Res, Metadata)] =
        modCtx(callCtx).flatMap(self.visitStreamingToUnaryCallTrailers(_, req, run))

    }
}

object ClientAspect {
  trait Default[F[_]] extends ClientAspect[F, F, Metadata] {
    override def visitUnaryToUnaryCall[Req, Res](
        callCtx: ClientCallContext[Req, Res, Metadata],
        req: Req,
        run: (Req, Metadata) => F[Res]
    ): F[Res] = run(req, callCtx.ctx)

    override def visitUnaryToStreamingCall[Req, Res](
        callCtx: ClientCallContext[Req, Res, Metadata],
        req: Req,
        run: (Req, Metadata) => Stream[F, Res]
    ): Stream[F, Res] = run(req, callCtx.ctx)

    override def visitStreamingToUnaryCall[Req, Res](
        callCtx: ClientCallContext[Req, Res, Metadata],
        req: Stream[F, Req],
        run: (Stream[F, Req], Metadata) => F[Res]
    ): F[Res] = run(req, callCtx.ctx)

    override def visitStreamingToStreamingCall[Req, Res](
        callCtx: ClientCallContext[Req, Res, Metadata],
        req: Stream[F, Req],
        run: (Stream[F, Req], Metadata) => Stream[F, Res]
    ): Stream[F, Res] = run(req, callCtx.ctx)

    override def visitUnaryToUnaryCallTrailers[Req, Res](
        callCtx: ClientCallContext[Req, Res, Metadata],
        req: Req,
        run: (Req, Metadata) => F[(Res, Metadata)]
    ): F[(Res, Metadata)] = run(req, callCtx.ctx)

    override def visitStreamingToUnaryCallTrailers[Req, Res](
        callCtx: ClientCallContext[Req, Res, Metadata],
        req: Stream[F, Req],
        run: (Stream[F, Req], Metadata) => F[(Res, Metadata)]
    ): F[(Res, Metadata)] = run(req, callCtx.ctx)
  }
}
