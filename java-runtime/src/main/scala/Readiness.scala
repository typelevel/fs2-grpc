package org.lyranthe.fs2_grpc
package java_runtime
package shared

import cats.Applicative
import cats.effect.Concurrent
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._

// Readiness implements respect for GRPC's backpressure on the sender
// - i.e. it delays sending into a channel if that channel
// is full.
private [java_runtime] class ReadinessImpl[F[_]](
  waiting: Ref[F, Option[Deferred[F, Unit]]]
)(implicit F: Concurrent[F]) extends Readiness[F] {
  def signal: F[Unit] = {
    waiting.getAndSet(None).flatMap {
      case None => F.unit
      case Some(wake) => wake.complete(())
    }
  }

  def whenReady(isReady: F[Boolean], action: F[Unit]): F[Unit] = {
    isReady.ifM(action, {
      Deferred[F, Unit].flatMap { wakeup =>
        waiting.set(wakeup.some) *>
          isReady.ifM(signal, F.unit) *> // trigger manually in case onReady was invoked before we installed wakeup
          wakeup.get *>
          action
      }
    })
  }
}

private [java_runtime] trait Readiness[F[_]] {
  def signal: F[Unit]

  def whenReady(isReady: F[Boolean], action: F[Unit]): F[Unit]
}

private [java_runtime] object Readiness {
  def apply[F[_]](implicit F: Concurrent[F]): F[Readiness[F]] =
    Ref[F].of(Option.empty[Deferred[F, Unit]]).map(new ReadinessImpl(_))

  def noop[F[_]](implicit F: Applicative[F]): Readiness[F] = new Readiness[F] {
    override def signal: F[Unit] = F.unit

    override def whenReady(isReady: F[Boolean], action: F[Unit]): F[Unit] = action
  }
}
