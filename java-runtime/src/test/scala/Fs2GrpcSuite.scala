package org.lyranthe.fs2_grpc
package java_runtime

import scala.concurrent.duration.FiniteDuration
import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.testkit.TestContext
import cats.effect.unsafe.{IORuntime, Scheduler}
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

    val runtime = IORuntime(ctx, ctx, scheduler, () => ())

    (ctx, runtime)
  }

  protected def runTest(name: String)(body: (TestContext, UnsafeRunner[IO]) => Unit): Unit =
    runTest0(name)((tc, _, ur) => body(tc, ur))

  protected def runTest0(name: String)(body: (TestContext, IORuntime, UnsafeRunner[IO]) => Unit): Unit = {
    test(name) {
      val (ec: TestContext, r: IORuntime) = createDeterministicRuntime
      val dispatcherF = Dispatcher[IO].allocated.unsafeToFuture()(r)
      ec.tick()
      val (dispatcher, shutdown) = dispatcherF.value.get.get
      val runner: UnsafeRunner[IO] = new UnsafeRunner[IO] {
        def unsafeRunAndForget[A](fa: IO[A]): Unit = dispatcher.unsafeRunAndForget(fa)
        def unsafeRunSync[A](fa: IO[A]): A = fa.unsafeRunSync()
      }

      body(ec, r, runner)
      shutdown.unsafeRunAndForget()
      ec.tickAll()
    }
  }
}
