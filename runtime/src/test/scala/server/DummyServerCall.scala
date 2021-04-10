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

import io.grpc.{Metadata, MethodDescriptor, ServerCall, Status}

import scala.collection.mutable.ArrayBuffer

class DummyServerCall extends ServerCall[String, Int] {
  val messages: ArrayBuffer[Int] = ArrayBuffer[Int]()
  var currentStatus: Option[Status] = None

  override def request(numMessages: Int): Unit = ()
  override def sendMessage(message: Int): Unit = {
    messages += message
    ()
  }
  override def sendHeaders(headers: Metadata): Unit = {
    ()
  }
  override def getMethodDescriptor: MethodDescriptor[String, Int] = ???
  override def close(status: Status, trailers: Metadata): Unit = {
    currentStatus = Some(status)
  }
  override def isCancelled: Boolean = false
}
