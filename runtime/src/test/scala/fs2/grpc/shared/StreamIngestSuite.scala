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
package shared

import cats.effect._
import munit._

class StreamIngestSuite extends CatsEffectSuite with CatsEffectFunFixtures {

  test("basic") {

    def run(emitN: Int, prefetchN: Int, takeN: Int, expectedReq: Int, expectedCount: Int) = {
      def capture(s: Stream[IO, Int]): IO[List[Int]] = s.take(takeN.toLong).compile.toList

      for {
        ref <- IO.ref(0)
        ingest <- StreamIngest[IO, Int](req => ref.update(_ + req), prefetchN)
        emitted = Stream.emits((1 to emitN)).covary[IO]
        _ <- emitted.evalTap(ingest.onMessage).compile.drain
        expected <- capture(emitted)
        messages <- capture(ingest.messages)
        requested <- ref.get
      } yield {
        assertEquals(messages.size, expectedCount)
        assertEquals(messages, expected)
        assertEquals(requested, expectedReq)
      }
    }

    run(emitN = 1, prefetchN = 1, takeN = 1, expectedReq = 0, expectedCount = 1) *>
      run(emitN = 1, prefetchN = 2, takeN = 1, expectedReq = 2, expectedCount = 1) *>
      run(emitN = 2, prefetchN = 2, takeN = 1, expectedReq = 0, expectedCount = 1) *>
      run(emitN = 2, prefetchN = 4, takeN = 1, expectedReq = 4, expectedCount = 1) *>
      run(emitN = 2, prefetchN = 1, takeN = 2, expectedReq = 0, expectedCount = 2) *>
      run(emitN = 2, prefetchN = 4, takeN = 2, expectedReq = 4, expectedCount = 2) *>
      run(emitN = 1024, prefetchN = 1024, takeN = 1024, expectedReq = 0, expectedCount = 1024) *>
      run(emitN = 1024, prefetchN = 2048, takeN = 1024, expectedReq = 2048, expectedCount = 1024) *>
      run(emitN = 1024, prefetchN = 1024, takeN = 1023, expectedReq = 0, expectedCount = 1023)
    run(emitN = 1024, prefetchN = 2048, takeN = 1023, expectedReq = 2048, expectedCount = 1023)

  }

}
