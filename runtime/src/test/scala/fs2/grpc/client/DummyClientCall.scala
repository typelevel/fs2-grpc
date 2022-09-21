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

import scala.collection.mutable.ArrayBuffer
import io.grpc._

class DummyClientCall extends ClientCall[String, Int] {
  var requested: Int = 0
  val messagesSent: ArrayBuffer[String] = ArrayBuffer[String]()
  var listener: Option[ClientCall.Listener[Int]] = None
  var ready = true
  var cancelled: Option[(String, Throwable)] = None
  var halfClosed = false

  override def start(responseListener: ClientCall.Listener[Int], headers: Metadata): Unit =
    listener = Some(responseListener)

  override def request(numMessages: Int): Unit = requested += numMessages

  override def cancel(message: String, cause: Throwable): Unit =
    cancelled = Some((message, cause))

  override def halfClose(): Unit =
    halfClosed = true

  override def sendMessage(message: String): Unit = {
    messagesSent += message
    ()
  }

  override def isReady: Boolean = ready

  def setIsReady(value: Boolean): Unit = {
    ready = value
    if (value) {
      listener.get.onReady()
    }
  }
}
