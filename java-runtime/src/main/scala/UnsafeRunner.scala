package org
package lyranthe
package fs2_grpc
package java_runtime

import cats.effect.std.Dispatcher

private[java_runtime] trait UnsafeRunner[F[_]] {
  def unsafeRunSync[A](fa: F[A]): A
  def unsafeRunAndForget[A](fa: F[A]): Unit
}

private[java_runtime] object UnsafeRunner {

  def apply[F[_]](dispatcher: Dispatcher[F]): UnsafeRunner[F] = new UnsafeRunner[F] {
    def unsafeRunSync[A](fa: F[A]): A = dispatcher.unsafeRunSync(fa)
    def unsafeRunAndForget[A](fa: F[A]): Unit = dispatcher.unsafeRunAndForget(fa)
  }

}
