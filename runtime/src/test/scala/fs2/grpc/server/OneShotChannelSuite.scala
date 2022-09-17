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

package fs2.grpc.server

import cats.effect._
import cats.effect.testkit.TestControl
import fs2._
import fs2.grpc.server.internal.OneShotChannel
import munit.CatsEffectFunFixtures
import munit.CatsEffectSuite
import scala.concurrent.duration._

class OneShotChannelSuite extends CatsEffectSuite with CatsEffectFunFixtures {

  def run(size: Int, producerDelay: Int, consumerDelay: Int): IO[Unit] = {
    for {
      ch <- OneShotChannel.empty[Int].to[IO]
      chunkN <- Ref[IO].of(0)
      stream <- ch
        .stream[IO]
        .chunks
        .evalTap(_ => chunkN.update(_ + 1))
        .unchunks
        .zipLeft(Stream.awakeDelay[IO](consumerDelay.seconds))
        .compile
        .toList
        .start
      _ <- Stream
        .range(0, size)
        .evalMap(c => ch.send(c).to[IO])
        .append(Stream.exec(ch.close().to[IO]))
        .zipLeft(Stream.awakeDelay[IO](producerDelay.seconds))
        .compile
        .drain
      stream <- stream.joinWithNever
      chunkN <- chunkN.get
    } yield {
      assertEquals(stream, Stream.range(0, size).toList)
      if (consumerDelay > producerDelay) {
        assert(clue(chunkN) < clue(size))
      } else {
        assertEquals(chunkN, size)
      }
    }
  }

  test("basic") {
    TestControl.executeEmbed(
      run(0, 1, 1) >>
        run(1, 1, 1) >>
        run(2, 1, 1) >>
        run(8, 1, 1)
    )
  }
  test("slow producer") {
    TestControl.executeEmbed(
      run(8, 2, 1) >>
        run(16, 4, 3)
    )
  }
  test("slow consumer") {
    TestControl.executeEmbed(
      run(8, 1, 2) >>
        run(16, 3, 4)
    )
  }
}
