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

import scala.concurrent.duration._
import cats.effect._
import munit._

class StreamIngestSuite extends CatsEffectSuite with CatsEffectFunFixtures {

  test("basic") {

    def run(prefetchN: Int, takeN: Int, expectedReq: Int, expectedCount: Int) = {
      for {
        ref <- IO.ref(0)
        ingest <- StreamIngest[IO, Int](req => ref.update(_ + req), prefetchN)
        _ <- Stream.emits((1 to prefetchN)).evalTap(ingest.onMessage).compile.drain
        messages <- ingest.messages.take(takeN.toLong).compile.toList
        requested <- ref.get
      } yield {
        assertEquals(messages.size, expectedCount)
        assertEquals(requested, expectedReq)
      }
    }

    run(prefetchN = 1, takeN = 1, expectedReq = 2 /* queue becomes empty */, expectedCount = 1) *>
      run(prefetchN = 2, takeN = 1, expectedReq = 2, expectedCount = 1) *>
      run(prefetchN = 1024, takeN = 1024, expectedReq = 2048 /* queue becomes empty */, expectedCount = 1024) *>
      run(prefetchN = 1024, takeN = 1023, expectedReq = 1024, expectedCount = 1023)

  }

  test("producer slower") {

    def run(prefetchN: Int, takeN: Int, expectedReq: Int, expectedCount: Int, delay: FiniteDuration) = {
      for {
        ref <- IO.ref(0)
        ingest <- StreamIngest[IO, Int](req => ref.update(_ + req), prefetchN)
        worker <- Stream
          .emits((1 to prefetchN))
          .evalTap(m => IO.sleep(delay) *> ingest.onMessage(m))
          .compile
          .drain
          .start
        messages <- ingest.messages.take(takeN.toLong).compile.toList
        requested <- ref.get
        _ <- worker.cancel
      } yield {
        assertEquals(messages.size, expectedCount)
        assertEquals(requested, expectedReq)
      }
    }

    run(prefetchN = 5, takeN = 1, expectedReq = 5, expectedCount = 1, delay = 200.millis) *>
      run(prefetchN = 10, takeN = 5, expectedReq = 10, expectedCount = 5, delay = 200.millis) *>
      run(prefetchN = 10, takeN = 10, expectedReq = 20, expectedCount = 10, delay = 200.millis)

  }

  test("consumer slower") {

    def run(prefetchN: Int, takeN: Int, expectedReq: Int, expectedCount: Int, delay: FiniteDuration) = {
      for {
        ref <- IO.ref(0)
        ingest <- StreamIngest[IO, Int](req => ref.update(_ + req), prefetchN)
        worker <- Stream
          .emits((1 to prefetchN))
          .evalTap(ingest.onMessage)
          .compile
          .drain
          .start
        messages <- ingest.messages.evalTap(_ => IO.sleep(delay)).take(takeN.toLong).compile.toList
        requested <- ref.get
        _ <- worker.cancel
      } yield {
        assertEquals(messages.size, expectedCount)
        assertEquals(requested, expectedReq)
      }
    }

    run(prefetchN = 5, takeN = 1, expectedReq = 5, expectedCount = 1, delay = 200.millis) *>
      run(prefetchN = 10, takeN = 5, expectedReq = 10, expectedCount = 5, delay = 200.millis) *>
      run(prefetchN = 10, takeN = 10, expectedReq = 20, expectedCount = 10, delay = 200.millis)

  }

}
