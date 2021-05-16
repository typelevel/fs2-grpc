package org.lyranthe.fs2_grpc
package java_runtime
package client

import scala.concurrent.ExecutionContext
import cats.effect._
import cats.effect.concurrent.Ref
import minitest._

object StreamIngestSuite extends SimpleTestSuite {

  implicit val CS: ContextShift[IO] =
    IO.contextShift(ExecutionContext.global)

  test("basic") {

    def run(prefetchN: Int, takeN: Int, expectedReq: Int, expectedCount: Int) = {
      for {
        ref <- Ref.of[IO, Int](0)
        ingest <- StreamIngest[IO, Int](req => ref.update(_ + req), prefetchN)
        _ <- fs2.Stream.emits((1 to prefetchN)).evalTap(m => ingest.onMessage(m)).compile.drain
        messages <- ingest.messages.take(takeN.toLong).compile.toList
        requested <- ref.get
      } yield {
        assertEquals(messages.size, expectedCount)
        assertEquals(requested, expectedReq)
      }
    }

    val test =
      run(prefetchN = 1, takeN = 1, expectedReq = 2 /* queue becomes empty */, expectedCount = 1) *>
        run(prefetchN = 2, takeN = 1, expectedReq = 2, expectedCount = 1) *>
        run(prefetchN = 1024, takeN = 1024, expectedReq = 2048 /* queue becomes empty */, expectedCount = 1024) *>
        run(prefetchN = 1024, takeN = 1023, expectedReq = 1024, expectedCount = 1023)

    test.unsafeRunSync()

  }

}
