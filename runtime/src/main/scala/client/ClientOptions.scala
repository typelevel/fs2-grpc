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

import io.grpc.{StatusRuntimeException, CallOptions}

sealed abstract class ClientOptions private (
    val prefetchN: Int,
    val callOptionsFn: CallOptions => CallOptions,
    val errorAdapter: PartialFunction[StatusRuntimeException, Exception]
) {

  private def copy(
      prefetchN: Int = this.prefetchN,
      callOptionsFn: CallOptions => CallOptions = this.callOptionsFn,
      errorAdapter: PartialFunction[StatusRuntimeException, Exception] = this.errorAdapter
  ): ClientOptions =
    new ClientOptions(prefetchN, callOptionsFn, errorAdapter) {}

  /** Prefetch up to @param n messages from a server. The client will try to keep the internal buffer filled according
    * to the provided value.
    *
    * If the provided value is less than 1 it defaults to 1.
    */
  def withPrefetchN(n: Int): ClientOptions =
    copy(prefetchN = math.max(n, 1))

  /** Adapt `io.grpc.StatusRuntimeException` into a different exception.
    */
  def withErrorAdapter(ea: PartialFunction[StatusRuntimeException, Exception]): ClientOptions =
    copy(errorAdapter = ea)

  /** Function that is applied on `io.grpc.CallOptions.DEFAULT` for each new RPC call.
    */
  def configureCallOptions(fn: CallOptions => CallOptions): ClientOptions =
    copy(callOptionsFn = fn)

}

object ClientOptions {

  val default: ClientOptions = new ClientOptions(
    prefetchN = 1,
    callOptionsFn = identity,
    errorAdapter = PartialFunction.empty[StatusRuntimeException, Exception]
  ) {}

}
