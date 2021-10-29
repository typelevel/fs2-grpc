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

import cats.syntax.all._
import cats.effect._
import cats.effect.std._
import munit._

class StreamIngestSuite extends CatsEffectSuite with CatsEffectFunFixtures {

  test("basic") {

    def run(prefetchN: Int, takeN: Int, expectedReq: Int) = for {

      rc <- IO.ref(0)
      grpc <- Queue.bounded[IO, Int](prefetchN)
      fetch = rc.get.flatMap(r => (rc.update(_ + 1) *> grpc.offer(r + 1)).whenA(r <= takeN))
      ingest <- StreamIngest[IO, Int](_ => fetch, prefetchN)
      _ <- grpc.offer(0)
      worker <- Stream.fromQueueUnterminated(grpc).evalTap(ingest.onMessage).compile.drain.start
      _ <- ingest.messages.take(takeN.toLong).compile.drain
      req <- rc.get
      _ <- worker.cancel

    } yield assertEquals(req, expectedReq)

    run(prefetchN = 1, takeN = 1, expectedReq = 1) *>
      run(prefetchN = 2, takeN = 1, expectedReq = 1) *>
      run(prefetchN = 2, takeN = 2, expectedReq = 2) *>
      run(prefetchN = 8, takeN = 16, expectedReq = 16) *>
      run(prefetchN = 16, takeN = 31, expectedReq = 31) *>
      run(prefetchN = 32, takeN = 32, expectedReq = 32) *>
      run(prefetchN = 64, takeN = 64, expectedReq = 64)

  }

}
