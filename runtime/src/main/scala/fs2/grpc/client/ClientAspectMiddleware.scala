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

import cats.arrow.FunctionK
import cats.~>
import fs2.Stream
import io.grpc.Metadata

/** A middleware for transforming effects within a [[ClientAspect]].
  *
  * This allows injecting custom logic (e.g., logging, metrics, error handling) around gRPC client calls without
  * modifying the underlying client implementation.
  *
  * @tparam F
  *   the effect type
  * @tparam Ctx
  *   the context type
  */
trait ClientAspectMiddleware[F[_], Ctx] {

  /** Transforms unary (single response) effects.
    *
    * @param callCtx
    *   the gRPC call context (method descriptor, metadata, etc.)
    * @return
    *   a natural transformation to apply to the effect
    */
  def unary[Req, Res](callCtx: ClientCallContext[Req, Res, Ctx]): F ~> F

  /** Transforms streaming effects.
    *
    * @param callCtx
    *   the gRPC call context (method descriptor, metadata, etc.)
    * @return
    *   a natural transformation to apply to the stream
    */
  def streaming[Req, Res](callCtx: ClientCallContext[Req, Res, Ctx]): Stream[F, *] ~> Stream[F, *]

  /** Composes this middleware with another, with `this` applied as the outer wrapper and `that` as the inner wrapper.
    *
    * For effects with before/after semantics, the execution order is: `this.before` -> `that.before` -> effect ->
    * `that.after` -> `this.after`
    */
  def compose(that: ClientAspectMiddleware[F, Ctx]): ClientAspectMiddleware[F, Ctx] =
    ClientAspectMiddleware.compose(this, that)
}

object ClientAspectMiddleware {

  /** Creates a middleware that does nothing (identity transformation). */
  def identity[F[_], Ctx]: ClientAspectMiddleware[F, Ctx] =
    new ClientAspectMiddleware[F, Ctx] {
      def unary[Req, Res](callCtx: ClientCallContext[Req, Res, Ctx]): F ~> F =
        FunctionK.id[F]

      def streaming[Req, Res](callCtx: ClientCallContext[Req, Res, Ctx]): Stream[F, *] ~> Stream[F, *] =
        FunctionK.id[Stream[F, *]]
    }

  /** Composes two middlewares, with `outer` applied as the outer wrapper and `inner` as the inner wrapper.
    *
    * For effects with before/after semantics, the execution order is: `outer.before` -> `inner.before` -> effect ->
    * `inner.after` -> `outer.after`
    */
  def compose[F[_], Ctx](
      outer: ClientAspectMiddleware[F, Ctx],
      inner: ClientAspectMiddleware[F, Ctx]
  ): ClientAspectMiddleware[F, Ctx] =
    new ClientAspectMiddleware[F, Ctx] {
      def unary[Req, Res](callCtx: ClientCallContext[Req, Res, Ctx]): F ~> F =
        outer.unary(callCtx).compose(inner.unary(callCtx))

      def streaming[Req, Res](callCtx: ClientCallContext[Req, Res, Ctx]): Stream[F, *] ~> Stream[F, *] =
        outer.streaming(callCtx).compose(inner.streaming(callCtx))
    }

  /** Composes multiple middlewares into one, with earlier middlewares as outer wrappers and later middlewares as inner
    * wrappers.
    *
    * For effects with before/after semantics, the execution order is: `first.before` -> `second.before` -> ... ->
    * effect -> ... -> `second.after` -> `first.after`
    */
  def composeAll[F[_], Ctx](middlewares: ClientAspectMiddleware[F, Ctx]*): ClientAspectMiddleware[F, Ctx] =
    middlewares.foldLeft(identity[F, Ctx])(_.compose(_))

  /** Wraps the client aspect with custom effect transformations.
    *
    * @param middleware
    *   the middleware providing the transformations
    * @param aspect
    *   the client aspect to wrap
    * @return
    *   a new client aspect with the middleware applied
    */
  def wrap[F[_], G[_], A](
      middleware: ClientAspectMiddleware[G, A],
      aspect: ClientAspect[F, G, A]
  ): ClientAspect[F, G, A] =
    new WrappedClientAspect(middleware, aspect)

  implicit class ClientAspectMiddlewareOps[F[_], G[_], Ctx](private val self: ClientAspect[F, G, Ctx]) extends AnyVal {

    /** Wraps the client aspect with custom effect transformations.
      *
      * @param middleware
      *   the middleware providing the transformations
      * @return
      *   a new client aspect with the middleware applied
      */
    def wrap(middleware: ClientAspectMiddleware[G, Ctx]): ClientAspect[F, G, Ctx] =
      ClientAspectMiddleware.wrap(middleware, self)

    /** Wraps the client aspect with multiple middlewares, with earlier middlewares as outer wrappers and later
      * middlewares as inner wrappers.
      */
    def wrapAll(middlewares: ClientAspectMiddleware[G, Ctx]*): ClientAspect[F, G, Ctx] =
      wrap(composeAll(middlewares: _*))
  }

  final private class WrappedClientAspect[F[_], G[_], Ctx](
      middleware: ClientAspectMiddleware[G, Ctx],
      inner: ClientAspect[F, G, Ctx]
  ) extends ClientAspect[F, G, Ctx] {

    def visitUnaryToUnaryCall[Req, Res](
        callCtx: ClientCallContext[Req, Res, Ctx],
        req: Req,
        run: (Req, Metadata) => G[Res]
    ): F[Res] =
      inner.visitUnaryToUnaryCall(callCtx, req, (r: Req, meta: Metadata) => middleware.unary(callCtx)(run(r, meta)))

    def visitUnaryToStreamingCall[Req, Res](
        callCtx: ClientCallContext[Req, Res, Ctx],
        req: Req,
        run: (Req, Metadata) => Stream[G, Res]
    ): Stream[F, Res] =
      inner.visitUnaryToStreamingCall(
        callCtx,
        req,
        (r: Req, meta: Metadata) => middleware.streaming(callCtx)(run(r, meta))
      )

    def visitStreamingToUnaryCall[Req, Res](
        callCtx: ClientCallContext[Req, Res, Ctx],
        req: Stream[F, Req],
        run: (Stream[G, Req], Metadata) => G[Res]
    ): F[Res] =
      inner.visitStreamingToUnaryCall(
        callCtx,
        req,
        (r: Stream[G, Req], meta: Metadata) => middleware.unary(callCtx)(run(r, meta))
      )

    def visitStreamingToStreamingCall[Req, Res](
        callCtx: ClientCallContext[Req, Res, Ctx],
        req: Stream[F, Req],
        run: (Stream[G, Req], Metadata) => Stream[G, Res]
    ): Stream[F, Res] =
      inner.visitStreamingToStreamingCall(
        callCtx,
        req,
        (r: Stream[G, Req], meta: Metadata) => middleware.streaming(callCtx)(run(r, meta))
      )

    def visitUnaryToUnaryCallTrailers[Req, Res](
        callCtx: ClientCallContext[Req, Res, Ctx],
        req: Req,
        run: (Req, Metadata) => G[(Res, Metadata)]
    ): F[(Res, Metadata)] =
      inner.visitUnaryToUnaryCallTrailers(
        callCtx,
        req,
        (r: Req, meta: Metadata) => middleware.unary(callCtx)(run(r, meta))
      )

    def visitStreamingToUnaryCallTrailers[Req, Res](
        callCtx: ClientCallContext[Req, Res, Ctx],
        req: Stream[F, Req],
        run: (Stream[G, Req], Metadata) => G[(Res, Metadata)]
    ): F[(Res, Metadata)] =
      inner.visitStreamingToUnaryCallTrailers(
        callCtx,
        req,
        (r: Stream[G, Req], meta: Metadata) => middleware.unary(callCtx)(run(r, meta))
      )
  }
}
