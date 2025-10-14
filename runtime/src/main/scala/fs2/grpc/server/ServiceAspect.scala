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

package fs2.grpc.server

import io.grpc._
import cats._
import cats.syntax.all._
import fs2.Stream

final case class ServiceCallContext[Req, Res](
    metadata: Metadata,
    methodDescriptor: MethodDescriptor[Req, Res]
)

trait ServiceAspect[F[_], G[_], A] { self =>
  def visitUnaryToUnaryCall[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: Req,
      run: (Req, A) => F[Res]
  ): G[Res]

  def visitUnaryToStreamingCall[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: Req,
      run: (Req, A) => fs2.Stream[F, Res]
  ): fs2.Stream[G, Res]

  def visitStreamingToUnaryCall[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: fs2.Stream[G, Req],
      run: (fs2.Stream[F, Req], A) => F[Res]
  ): G[Res]

  def visitStreamingToStreamingCall[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: fs2.Stream[G, Req],
      run: (fs2.Stream[F, Req], A) => fs2.Stream[F, Res]
  ): fs2.Stream[G, Res]

  def visitUnaryToUnaryCallTrailers[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: Req,
      run: (Req, A) => F[(Res, Metadata)]
  ): G[(Res, Metadata)]

  def visitStreamingToUnaryCallTrailers[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: fs2.Stream[G, Req],
      run: (fs2.Stream[F, Req], A) => F[(Res, Metadata)]
  ): G[(Res, Metadata)]

  def modify[B](f: A => F[B])(implicit F: Monad[F]): ServiceAspect[F, G, B] =
    new ServiceAspect[F, G, B] {
      override def visitUnaryToUnaryCall[Req, Res](
          callCtx: ServiceCallContext[Req, Res],
          req: Req,
          request: (Req, B) => F[Res]
      ): G[Res] =
        self.visitUnaryToUnaryCall[Req, Res](
          callCtx,
          req,
          (req, a) => f(a).flatMap(request(req, _))
        )

      override def visitUnaryToStreamingCall[Req, Res](
          callCtx: ServiceCallContext[Req, Res],
          req: Req,
          request: (Req, B) => Stream[F, Res]
      ): Stream[G, Res] =
        self.visitUnaryToStreamingCall[Req, Res](
          callCtx,
          req,
          (req, a) => fs2.Stream.eval(f(a)).flatMap(request(req, _))
        )

      override def visitStreamingToUnaryCall[Req, Res](
          callCtx: ServiceCallContext[Req, Res],
          req: fs2.Stream[G, Req],
          request: (Stream[F, Req], B) => F[Res]
      ): G[Res] =
        self.visitStreamingToUnaryCall[Req, Res](
          callCtx,
          req,
          (req, a) => f(a).flatMap(request(req, _))
        )

      override def visitStreamingToStreamingCall[Req, Res](
          callCtx: ServiceCallContext[Req, Res],
          req: fs2.Stream[G, Req],
          request: (Stream[F, Req], B) => Stream[F, Res]
      ): Stream[G, Res] =
        self.visitStreamingToStreamingCall[Req, Res](
          callCtx,
          req,
          (req, a) => fs2.Stream.eval(f(a)).flatMap(request(req, _))
        )

      override def visitUnaryToUnaryCallTrailers[Req, Res](
          callCtx: ServiceCallContext[Req, Res],
          req: Req,
          request: (Req, B) => F[(Res, Metadata)]
      ): G[(Res, Metadata)] =
        self.visitUnaryToUnaryCallTrailers[Req, Res](
          callCtx,
          req,
          (req, a) => f(a).flatMap(request(req, _))
        )

      override def visitStreamingToUnaryCallTrailers[Req, Res](
          callCtx: ServiceCallContext[Req, Res],
          req: fs2.Stream[G, Req],
          request: (Stream[F, Req], B) => F[(Res, Metadata)]
      ): G[(Res, Metadata)] =
        self.visitStreamingToUnaryCallTrailers[Req, Res](
          callCtx,
          req,
          (req, a) => f(a).flatMap(request(req, _))
        )
    }
}

object ServiceAspect {
  trait Default[F[_]] extends ServiceAspect[F, F, Metadata] {
    override def visitUnaryToUnaryCall[Req, Res](
        callCtx: ServiceCallContext[Req, Res],
        req: Req,
        request: (Req, Metadata) => F[Res]
    ): F[Res] = request(req, callCtx.metadata)

    override def visitUnaryToStreamingCall[Req, Res](
        callCtx: ServiceCallContext[Req, Res],
        req: Req,
        request: (Req, Metadata) => Stream[F, Res]
    ): Stream[F, Res] = request(req, callCtx.metadata)

    override def visitStreamingToUnaryCall[Req, Res](
        callCtx: ServiceCallContext[Req, Res],
        req: fs2.Stream[F, Req],
        request: (Stream[F, Req], Metadata) => F[Res]
    ): F[Res] = request(req, callCtx.metadata)

    override def visitStreamingToStreamingCall[Req, Res](
        callCtx: ServiceCallContext[Req, Res],
        req: fs2.Stream[F, Req],
        request: (Stream[F, Req], Metadata) => Stream[F, Res]
    ): Stream[F, Res] = request(req, callCtx.metadata)

    override def visitUnaryToUnaryCallTrailers[Req, Res](
        callCtx: ServiceCallContext[Req, Res],
        req: Req,
        request: (Req, Metadata) => F[(Res, Metadata)]
    ): F[(Res, Metadata)] = request(req, callCtx.metadata)

    override def visitStreamingToUnaryCallTrailers[Req, Res](
        callCtx: ServiceCallContext[Req, Res],
        req: fs2.Stream[F, Req],
        request: (Stream[F, Req], Metadata) => F[(Res, Metadata)]
    ): F[(Res, Metadata)] = request(req, callCtx.metadata)
  }
}
