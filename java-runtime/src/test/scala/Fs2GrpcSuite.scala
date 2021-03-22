package org.lyranthe.fs2_grpc
package java_runtime

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
