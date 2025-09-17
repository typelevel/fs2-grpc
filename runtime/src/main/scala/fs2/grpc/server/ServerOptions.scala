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
package server

sealed abstract class ServerOptions private (
    val prefetchN: Int,
    val callOptionsFn: ServerCallOptions => ServerCallOptions
) {

  private def copy(
      prefetchN: Int = this.prefetchN,
      callOptionsFn: ServerCallOptions => ServerCallOptions = this.callOptionsFn
  ): ServerOptions = new ServerOptions(prefetchN, callOptionsFn) {}

  /** Prefetch up to @param n messages from a client. The server will try to keep the internal buffer filled according
    * to the provided value.
    *
    * If the provided value is less than 1 it defaults to 1.
    */
  def withPrefetchN(n: Int): ServerOptions =
    copy(prefetchN = math.max(n, 1))

  /** Function that is applied on `fs2.grpc.ServerCallOptions.default` for each new RPC call.
    */
  def configureCallOptions(fn: ServerCallOptions => ServerCallOptions): ServerOptions =
    copy(callOptionsFn = fn)
}

object ServerOptions {

  val default: ServerOptions = new ServerOptions(
    prefetchN = 1,
    callOptionsFn = identity
  ) {}

}
