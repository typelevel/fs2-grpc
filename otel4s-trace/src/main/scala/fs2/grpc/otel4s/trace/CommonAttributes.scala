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

package fs2.grpc.otel4s.trace

import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.semconv.attributes.RpcAttributes

/** @see
  *   [[https://opentelemetry.io/docs/specs/semconv/rpc/rpc-spans/]]
  */
private[trace] object CommonAttributes {

  val rpcSystem: Attribute[String] =
    RpcAttributes.RpcSystemName(RpcAttributes.RpcSystemNameValue.Grpc.value)

  def rpcMethod(method: String): Attribute[String] =
    RpcAttributes.RpcMethod(method)

  def rpcStatusCode(statusCode: String): Attribute[String] =
    RpcAttributes.RpcStatusCode(statusCode)

}
