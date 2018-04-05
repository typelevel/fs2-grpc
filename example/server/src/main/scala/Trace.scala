import cats.data.StateT
import cats.effect.{Effect, IO, Sync}
import cats.implicits._
import io.opencensus.trace.{Span, Tracing}

case class Trace[F[_], A](v: StateT[F, Span, A]) extends AnyVal {
  def withSpan(spanName: String)(implicit F: Sync[F]): Trace[F, A] = {
    def run(prevSpan: Span): F[A] = {
      for {
        newSpan <- F.delay(
          Tracing.getTracer
            .spanBuilderWithExplicitParent(spanName, prevSpan)
            .startSpan())
        result <- (v.runA(newSpan).attempt <* F.delay(newSpan.end())).rethrow
      } yield result
    }

    Trace(StateT.inspectF(run))
  }
}

object Trace {
  def addAnnotation[F[_]](description: String)(
      implicit F: Effect[F]): Trace[F, Unit] =
    Trace(StateT.inspectF { span =>
      F.delay(span.addAnnotation(description))
    })

  def liftF[F[_], A](f: F[A])(implicit F: Effect[F]): Trace[F, A] =
    Trace(StateT.inspectF { span =>
      F.delay(Tracing.getTracer.withSpan(span)) *> f
    })

  implicit def traceInstance[F[_]](implicit F: Effect[F]): Effect[Trace[F, ?]] =
    new Effect[Trace[F, ?]] {
      override def runAsync[A](fa: Trace[F, A])(
          cb: Either[Throwable, A] => IO[Unit]): IO[Unit] =
        F.runAsync(fa.v.runA(Tracing.getTracer.getCurrentSpan))(cb)

      override def async[A](
          k: (Either[Throwable, A] => Unit) => Unit): Trace[F, A] = {
        Trace(StateT.liftF(F.async(k)))
      }

      override def suspend[A](thunk: => Trace[F, A]): Trace[F, A] = {
        Trace(StateT { span =>
          F.suspend(thunk.v.run(span))
        })
      }

      override def pure[A](x: A): Trace[F, A] = {
        Trace(StateT.pure(x))
      }

      override def flatMap[A, B](fa: Trace[F, A])(
          f: A => Trace[F, B]): Trace[F, B] = {
        Trace(StateT.inspectF { span =>
          F.flatMap(fa.v.runA(span))(f(_).v.runA(span))
        })
      }

      override def tailRecM[A, B](a: A)(
          f: A => Trace[F, Either[A, B]]): Trace[F, B] = {
        Trace(StateT.inspectF { span =>
          F.tailRecM(a)(f(_).v.runA(span))
        })
      }

      override def raiseError[A](e: Throwable): Trace[F, A] = {
        Trace(StateT.liftF(F.raiseError(e)))
      }

      override def handleErrorWith[A](fa: Trace[F, A])(
          f: Throwable => Trace[F, A]): Trace[F, A] = {
        Trace(StateT.inspectF { span =>
          F.handleErrorWith(fa.v.runA(span))(f(_).v.runA(span))
        })
      }
    }
}