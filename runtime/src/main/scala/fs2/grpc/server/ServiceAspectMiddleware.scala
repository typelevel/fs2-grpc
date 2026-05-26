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

import cats.arrow.FunctionK
import cats.~>
import fs2.Stream
import io.grpc.Metadata

/** A middleware for transforming effects within a [[ServiceAspect]].
  *
  * This allows injecting custom logic (e.g., logging, metrics, error handling) around gRPC service calls without
  * modifying the underlying service implementation.
  *
  * @tparam F
  *   the effect type
  * @tparam Ctx
  *   the context type
  */
trait ServiceAspectMiddleware[F[_], Ctx] {

  /** Transforms unary (single response) effects.
    *
    * @param callCtx
    *   the gRPC call context (method descriptor, metadata, etc.)
    * @param ctx
    *   the user context for the current call
    * @return
    *   a natural transformation to apply to the effect
    */
  def unary[Req, Res](callCtx: ServiceCallContext[Req, Res], ctx: Ctx): F ~> F

  /** Transforms streaming effects.
    *
    * @param callCtx
    *   the gRPC call context (method descriptor, metadata, etc.)
    * @param ctx
    *   the user context for the current call
    * @return
    *   a natural transformation to apply to the stream
    */
  def streaming[Req, Res](callCtx: ServiceCallContext[Req, Res], ctx: Ctx): Stream[F, *] ~> Stream[F, *]

  /** Composes this middleware with another, with `this` applied as the outer wrapper and `that` as the inner wrapper.
    *
    * For effects with before/after semantics, the execution order is: `this.before` -> `that.before` -> effect ->
    * `that.after` -> `this.after`
    */
  def compose(that: ServiceAspectMiddleware[F, Ctx]): ServiceAspectMiddleware[F, Ctx] =
    ServiceAspectMiddleware.compose(this, that)
}

object ServiceAspectMiddleware {

  /** Creates a middleware that does nothing (identity transformation). */
  def identity[F[_], Ctx]: ServiceAspectMiddleware[F, Ctx] =
    new ServiceAspectMiddleware[F, Ctx] {
      def unary[Req, Res](callCtx: ServiceCallContext[Req, Res], ctx: Ctx): F ~> F =
        FunctionK.id[F]

      def streaming[Req, Res](callCtx: ServiceCallContext[Req, Res], ctx: Ctx): Stream[F, *] ~> Stream[F, *] =
        FunctionK.id[Stream[F, *]]
    }

  /** Composes two middlewares, with `outer` applied as the outer wrapper and `inner` as the inner wrapper.
    *
    * For effects with before/after semantics, the execution order is: `outer.before` -> `inner.before` -> effect ->
    * `inner.after` -> `outer.after`
    */
  def compose[F[_], Ctx](
      outer: ServiceAspectMiddleware[F, Ctx],
      inner: ServiceAspectMiddleware[F, Ctx]
  ): ServiceAspectMiddleware[F, Ctx] =
    new ServiceAspectMiddleware[F, Ctx] {
      def unary[Req, Res](callCtx: ServiceCallContext[Req, Res], ctx: Ctx): F ~> F =
        outer.unary(callCtx, ctx).compose(inner.unary(callCtx, ctx))

      def streaming[Req, Res](callCtx: ServiceCallContext[Req, Res], ctx: Ctx): Stream[F, *] ~> Stream[F, *] =
        outer.streaming(callCtx, ctx).compose(inner.streaming(callCtx, ctx))
    }

  /** Composes multiple middlewares into one, with earlier middlewares as outer wrappers and later middlewares as inner
    * wrappers.
    *
    * For effects with before/after semantics, the execution order is: `first.before` -> `second.before` -> ... ->
    * effect -> ... -> `second.after` -> `first.after`
    */
  def composeAll[F[_], Ctx](middlewares: ServiceAspectMiddleware[F, Ctx]*): ServiceAspectMiddleware[F, Ctx] =
    middlewares.foldLeft(identity[F, Ctx])(_.compose(_))

  /** Wraps the service aspect with custom effect transformations.
    *
    * @param middleware
    *   the middleware providing the transformations
    * @param aspect
    *   the service aspect to wrap
    * @return
    *   a new service aspect with the middleware applied
    */
  def wrap[F[_], G[_], A](
      middleware: ServiceAspectMiddleware[F, A],
      aspect: ServiceAspect[F, G, A]
  ): ServiceAspect[F, G, A] =
    new WrappedServiceAspect(middleware, aspect)

  implicit class ServiceAspectMiddlewareOps[F[_], G[_], Ctx](private val self: ServiceAspect[F, G, Ctx])
      extends AnyVal {

    /** Wraps the service aspect with custom effect transformations.
      *
      * @param middleware
      *   the middleware providing the transformations
      * @return
      *   a new service aspect with the middleware applied
      */
    def wrap(middleware: ServiceAspectMiddleware[F, Ctx]): ServiceAspect[F, G, Ctx] =
      ServiceAspectMiddleware.wrap(middleware, self)

    /** Wraps the service aspect with multiple middlewares, with earlier middlewares as outer wrappers and later
      * middlewares as inner wrappers.
      */
    def wrapAll(middlewares: ServiceAspectMiddleware[F, Ctx]*): ServiceAspect[F, G, Ctx] =
      wrap(composeAll(middlewares: _*))
  }

  final private class WrappedServiceAspect[F[_], G[_], Ctx](
      middleware: ServiceAspectMiddleware[F, Ctx],
      inner: ServiceAspect[F, G, Ctx]
  ) extends ServiceAspect[F, G, Ctx] {

    def visitUnaryToUnaryCall[Req, Res](
        callCtx: ServiceCallContext[Req, Res],
        req: Req,
        run: (Req, Ctx) => F[Res]
    ): G[Res] =
      inner.visitUnaryToUnaryCall(callCtx, req, (r: Req, a: Ctx) => middleware.unary(callCtx, a)(run(r, a)))

    def visitUnaryToStreamingCall[Req, Res](
        callCtx: ServiceCallContext[Req, Res],
        req: Req,
        run: (Req, Ctx) => Stream[F, Res]
    ): Stream[G, Res] =
      inner.visitUnaryToStreamingCall(
        callCtx,
        req,
        (r: Req, a: Ctx) => middleware.streaming(callCtx, a)(run(r, a))
      )

    def visitStreamingToUnaryCall[Req, Res](
        callCtx: ServiceCallContext[Req, Res],
        req: Stream[G, Req],
        run: (Stream[F, Req], Ctx) => F[Res]
    ): G[Res] =
      inner.visitStreamingToUnaryCall(
        callCtx,
        req,
        (r: Stream[F, Req], a: Ctx) => middleware.unary(callCtx, a)(run(r, a))
      )

    def visitStreamingToStreamingCall[Req, Res](
        callCtx: ServiceCallContext[Req, Res],
        req: Stream[G, Req],
        run: (Stream[F, Req], Ctx) => Stream[F, Res]
    ): Stream[G, Res] =
      inner.visitStreamingToStreamingCall(
        callCtx,
        req,
        (r: Stream[F, Req], a: Ctx) => middleware.streaming(callCtx, a)(run(r, a))
      )

    def visitUnaryToUnaryCallTrailers[Req, Res](
        callCtx: ServiceCallContext[Req, Res],
        req: Req,
        run: (Req, Ctx) => F[(Res, Metadata)]
    ): G[(Res, Metadata)] =
      inner.visitUnaryToUnaryCallTrailers(
        callCtx,
        req,
        (r: Req, a: Ctx) => middleware.unary(callCtx, a)(run(r, a))
      )

    def visitStreamingToUnaryCallTrailers[Req, Res](
        callCtx: ServiceCallContext[Req, Res],
        req: Stream[G, Req],
        run: (Stream[F, Req], Ctx) => F[(Res, Metadata)]
    ): G[(Res, Metadata)] =
      inner.visitStreamingToUnaryCallTrailers(
        callCtx,
        req,
        (r: Stream[F, Req], a: Ctx) => middleware.unary(callCtx, a)(run(r, a))
      )
  }
}
