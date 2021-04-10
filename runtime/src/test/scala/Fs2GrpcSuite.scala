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

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.testkit.TestContext
import cats.effect.unsafe.{IORuntime, IORuntimeConfig, Scheduler}
import munit._

class Fs2GrpcSuite extends CatsEffectSuite with CatsEffectFunFixtures {

  protected def createDeterministicRuntime: (TestContext, IORuntime) = {

    val ctx = TestContext()
    val scheduler = new Scheduler {
      def sleep(delay: FiniteDuration, action: Runnable): Runnable = {
        val cancel = ctx.schedule(delay, action)
        () => cancel()
      }

      def nowMillis() = ctx.now().toMillis
      def monotonicNanos() = ctx.now().toNanos
    }

    val runtime = IORuntime(ctx, ctx, scheduler, () => (), IORuntimeConfig())

    (ctx, runtime)
  }

  protected def runTest(name: String)(body: (TestContext, Dispatcher[IO]) => Unit): Unit =
    runTest0(name)((tc, _, d) => body(tc, d))

  protected def runTest0(name: String)(body: (TestContext, IORuntime, Dispatcher[IO]) => Unit): Unit = {
    test(name) {
      val (ec: TestContext, r: IORuntime) = createDeterministicRuntime
      val dispatcherF = Dispatcher[IO].allocated.unsafeToFuture()(r)
      ec.tick()
      val (dispatcher, shutdown) = dispatcherF.value.get.get
      val fakeDispatcher: Dispatcher[IO] = new Dispatcher[IO] {
        def unsafeToFutureCancelable[A](fa: IO[A]): (Future[A], () => Future[Unit]) =
          dispatcher.unsafeToFutureCancelable(fa)
        override def unsafeRunSync[A](fa: IO[A]): A =
          fa.unsafeRunSync()
      }

      body(ec, r, fakeDispatcher)
      shutdown.unsafeRunAndForget()
      ec.tickAll()
    }
  }
}
