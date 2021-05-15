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

sealed abstract class ServerCompressor(val name: String) extends Product with Serializable
case object GzipCompressor extends ServerCompressor("gzip")

sealed abstract class ServerCallOptions private (
    val compressor: Option[ServerCompressor],
    val messageCompression: Boolean
) {

  private def copy(
      compressor: Option[ServerCompressor] = this.compressor,
      messageCompression: Boolean = this.messageCompression
  ): ServerCallOptions =
    new ServerCallOptions(compressor, messageCompression) {}

  /** Enables per-message compression.  If no message encoding has been negotiated,
    * this is a no-op. By default per-message compression is enabled,
    * but may not have any effect if compression is not enabled on the call.
    */
  def withMessageCompression(enabled: Boolean): ServerCallOptions =
    copy(messageCompression = enabled)

  /** Sets the compression algorithm for a call. Default gRPC
    * servers support the "gzip" compressor.
    */
  def withServerCompressor(sc: Option[ServerCompressor]): ServerCallOptions =
    copy(compressor = sc)

}

object ServerCallOptions {
  val default: ServerCallOptions = new ServerCallOptions(
    compressor = None,
    messageCompression = true
  ) {}
}
