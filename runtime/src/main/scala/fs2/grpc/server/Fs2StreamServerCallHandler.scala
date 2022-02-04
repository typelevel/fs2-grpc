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

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import cats.effect.Async
import cats.effect.std.Dispatcher
import io.grpc.Metadata
import io.grpc.ServerCall
import scala.annotation.tailrec
import scala.collection.immutable.Queue
import fs2._
import io.grpc.ServerCallHandler

object Fs2StreamServerCallHandler {
  import Fs2StatefulServerCall.Cancel

  private def mkListener[F[_]: Async, Request, Response](
      run: Stream[F, Request] => Cancel,
      call: ServerCall[Request, Response]
  ): ServerCall.Listener[Request] =
    new ServerCall.Listener[Request] {
      private[this] val ch = UnsafeChannel.empty[Request]
      private[this] val cancel: Cancel = run(ch.stream.mapChunks { chunk =>
        val size = chunk.size
        if (size > 0) call.request(size)
        chunk
      })

      override def onCancel(): Unit = {
        cancel()
        ()
      }

      override def onMessage(message: Request): Unit =
        ch.send(message)

      override def onHalfClose(): Unit =
        ch.close()
    }

  def unary[F[_]: Async, Request, Response](
      impl: (Stream[F, Request], Metadata) => F[Response],
      options: ServerOptions,
      dispatcher: Dispatcher[F]
  ): ServerCallHandler[Request, Response] =
    new ServerCallHandler[Request, Response] {
      private val opt = options.callOptionsFn(ServerCallOptions.default)

      def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        val responder = Fs2StatefulServerCall.setup(opt, call, dispatcher)
        call.request(1) // prefetch size
        mkListener[F, Request, Response](req => responder.unary(impl(req, headers)), call)
      }
    }

  def stream[F[_]: Async, Request, Response](
      impl: (Stream[F, Request], Metadata) => Stream[F, Response],
      options: ServerOptions,
      dispatcher: Dispatcher[F]
  ): ServerCallHandler[Request, Response] =
    new ServerCallHandler[Request, Response] {
      private val opt = options.callOptionsFn(ServerCallOptions.default)

      def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        val responder = Fs2StatefulServerCall.setup(opt, call, dispatcher)
        call.request(1) // prefetch size
        mkListener[F, Request, Response](req => responder.stream(impl(req, headers)), call)
      }
    }
}

import UnsafeChannel._

final class UnsafeChannel[A] extends AtomicReference[State[A]](new State.Open(Queue.empty)) {

  import UnsafeChannel.State._
  import scala.annotation.nowarn

  @nowarn
  @tailrec
  def send(a: A): Unit = {
    get() match {
      case cur: Buffered[A] =>
        if (!compareAndSet(cur, cur.append(a))) {
          send(a)
        }
      case s: Suspended[A] =>
        lazySet(Consumed)
        s.send(new Open(Queue(a)))
    }
  }

  @tailrec
  def close(): Unit =
    get() match {
      case open: Open[_] =>
        if (!compareAndSet(open, open.done())) {
          close()
        }
      case s: Suspended[_] =>
        s.send(new Completed(Queue.empty))
      case _ => // unexpected
    }

  import fs2._

  def stream[F[_]](implicit F: Async[F]): Stream[F, A] = {
    @nowarn
    def go(): Pull[F, A, Unit] =
      Pull
        .suspend {
          val got = getAndSet(Consumed)
          if (got eq Consumed) {
            Pull.eval(F.async[State[A]] { cb =>
              F.delay {
                val next = new Suspended[A](s => cb(Right(s)))
                if (!compareAndSet(Consumed, next)) {
                  cb(Right(Consumed))
                  None
                } else {
                  Some(F.delay(cb(Right(Cancelled))))
                }
              }
            })
          } else Pull.pure(got)
        }
        .flatMap {
          case open: Open[A] => Pull.output(Chunk.queue(open.queue)) >> go()
          case buffered: Completed[A] => Pull.output(Chunk.queue(buffered.queue))
          case suspended: Suspended[A] => Pull.done
        }

    go().stream
  }
}

object UnsafeChannel {
  def empty[A]: UnsafeChannel[A] = new UnsafeChannel[A]

  sealed trait State[+A]

  object State {
    private[UnsafeChannel] val Consumed: Open[Nothing] = new Open(Queue.empty)
    private[UnsafeChannel] val Cancelled: Completed[Nothing] = new Completed(Queue.empty)

    trait Buffered[A] {
      self: State[A] =>
      def append(a: A): State[A]

      def queue: Queue[A]
    }

    class Open[A](val queue: Queue[A]) extends State[A] with Buffered[A] {
      override def append(a: A): State[A] = new Open(queue.enqueue(a))

      def done(): Completed[A] = new Completed(queue)
    }

    class Completed[A](val queue: Queue[A]) extends State[A] with Buffered[A] {
      override def append(a: A): State[A] = new Completed(queue.enqueue(a))
    }

    class Suspended[A](val f: State[A] => Unit) extends AtomicBoolean(false) with State[A] {
      def send(state: State[A]): Unit =
        if (!getAndSet(true)) {
          f(state)
        }
    }
  }
}
