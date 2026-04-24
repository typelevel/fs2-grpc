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

import io.grpc.Metadata
import org.typelevel.otel4s.context.propagation.TextMapGetter

import scala.jdk.CollectionConverters._

object TextMapGetters {

  val asciiStringMetadataTextMapGetter: TextMapGetter[Metadata] =
    new TextMapGetter[Metadata] {
      def get(carrier: Metadata, key: String): Option[String] =
        Option(carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)))

      def keys(carrier: Metadata): Iterable[String] =
        carrier.keys().asScala
    }

}
