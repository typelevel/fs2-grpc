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

package fs2.grpc.server.internal

import cats.effect._
import cats.syntax.functor._
import fs2._
import fs2.grpc.server.internal.OneShotChannel.State
import scala.collection.immutable.Queue

private[server] final class OneShotChannel[A](val state: Ref[SyncIO, State[A]]) extends AnyVal {

  import State._

  /** Send message to stream.
    */
  def send(a: A): SyncIO[Unit] =
    state
      .modify {
        case open: Open[A] => (open.append(a), SyncIO.unit)
        case s: Suspended[A] => (State.consumed, s.resume(State.open(a)))
        case closed => (closed, SyncIO.unit)
      }
      .flatMap(identity)

  /** Close stream.
    */
  def close(): SyncIO[Unit] =
    state
      .modify {
        case open: Open[A] => (open.close(), SyncIO.unit)
        case s: Suspended[A] => (State.done, s.resume(State.done))
        case closed => (closed, SyncIO.unit)
      }
      .flatMap(identity)

  import fs2._

  /** This method can be called at most once
    */
  def stream[F[_]](implicit F: Async[F]): Stream[F, A] = {
    def go(): Pull[F, A, Unit] =
      Pull
        .eval(state.getAndSet(State.consumed).to[F])
        .flatMap {
          case Consumed =>
            Pull.eval(F.async[State[A]] { cb =>
              val next = new Suspended[A](s => cb(Right(s)))
              state
                .modify {
                  case Consumed => (next, None)
                  case other => (State.consumed, Some(other))
                }
                .to[F]
                .map {
                  case Some(received) =>
                    cb(Right(received))
                    None
                  case None =>
                    Some(state.set(State.consumed).to[F])
                }
            })
          case other => Pull.pure(other)
        }
        .flatMap {
          case open: Open[A] => open.toPull >> go()
          case other => other.toPull
        }

    go().stream
  }
}

private[server] object OneShotChannel {
  def empty[A]: SyncIO[OneShotChannel[A]] =
    Ref[SyncIO].of[State[A]](State.consumed).map(new OneShotChannel[A](_))

  sealed trait State[A] {
    def toPull[F[_]: Sync]: Pull[F, A, Unit]
  }

  object State {
    class UnexpectedState extends RuntimeException
    private[OneShotChannel] val Consumed: State[Nothing] = new Open(Queue.empty)
    def consumed[A]: State[A] = Consumed.asInstanceOf[State[A]]

    def done[A]: State[A] = new Closed(Queue.empty)

    def open[A](a: A): Open[A] = new Open(Queue(a))

    class Open[A](queue: Queue[A]) extends State[A] {
      def append(a: A): Open[A] = new Open(queue.enqueue(a))

      def toPull[F[_]: Sync]: Pull[F, A, Unit] = Pull.output(Chunk.queue(queue))

      def close(): State[A] = new Closed(queue)
    }

    class Closed[A](queue: Queue[A]) extends State[A] {
      def toPull[F[_]: Sync]: Pull[F, A, Unit] = Pull.output(Chunk.queue(queue))
    }

    class Suspended[A](f: State[A] => Unit) extends State[A] {
      def resume(state: State[A]): SyncIO[Unit] = SyncIO(f(state))

      def toPull[F[_]: Sync]: Pull[F, A, Unit] = Pull.raiseError(new UnexpectedState) // never happened
    }
  }
}
