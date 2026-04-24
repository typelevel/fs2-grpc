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

package fs2.grpc.otel4s.trace

import cats.syntax.semigroup._
import io.grpc.{Status, StatusException, StatusRuntimeException}
import org.typelevel.otel4s.trace.{SpanFinalizer, StatusCode}
import cats.effect.kernel.Resource
import org.typelevel.otel4s.semconv.attributes.ErrorAttributes

private[trace] object GrpcSpanFinalizers {

  private val serverErrorCodes = Set(
    Status.Code.UNKNOWN,
    Status.Code.DEADLINE_EXCEEDED,
    Status.Code.UNIMPLEMENTED,
    Status.Code.INTERNAL,
    Status.Code.UNAVAILABLE,
    Status.Code.DATA_LOSS
  )

  val client: SpanFinalizer.Strategy = {
    case Resource.ExitCase.Succeeded =>
      SpanFinalizer.addAttribute(CommonAttributes.rpcResponseStatusCode(Status.Code.OK.name()))

    case Resource.ExitCase.Canceled =>
      val code = Status.Code.CANCELLED.name()

      SpanFinalizer.addAttributes(
        CommonAttributes.rpcResponseStatusCode(code),
        ErrorAttributes.ErrorType(code)
      ) |+|
        SpanFinalizer.setStatus(StatusCode.Error, code)

    case Resource.ExitCase.Errored(error) =>
      val code = clientStatusCode(error).name()

      SpanFinalizer.addAttributes(
        CommonAttributes.rpcResponseStatusCode(code),
        ErrorAttributes.ErrorType(code)
      ) |+|
        SpanFinalizer.recordException(error) |+|
        SpanFinalizer.setStatus(StatusCode.Error, code)
  }

  val server: SpanFinalizer.Strategy = {
    case Resource.ExitCase.Succeeded =>
      SpanFinalizer.addAttribute(CommonAttributes.rpcResponseStatusCode(Status.Code.OK.name()))

    case Resource.ExitCase.Canceled =>
      SpanFinalizer.addAttribute(CommonAttributes.rpcResponseStatusCode(Status.Code.CANCELLED.name()))

    case Resource.ExitCase.Errored(error) =>
      val code = serverStatusCode(error)
      val base = SpanFinalizer.addAttribute(CommonAttributes.rpcResponseStatusCode(code.name()))

      if (serverErrorCodes(code)) {
        base |+|
          SpanFinalizer.addAttribute(ErrorAttributes.ErrorType(code.name())) |+|
          SpanFinalizer.recordException(error) |+|
          SpanFinalizer.setStatus(StatusCode.Error, code.name())
      } else {
        base
      }
  }

  private def clientStatusCode(error: Throwable): Status.Code =
    error match {
      case ex: StatusException => ex.getStatus.getCode
      case ex: StatusRuntimeException => ex.getStatus.getCode
      case _ => Status.Code.UNKNOWN
    }

  private def serverStatusCode(error: Throwable): Status.Code =
    error match {
      case ex: StatusException => ex.getStatus.getCode
      case ex: StatusRuntimeException => ex.getStatus.getCode
      case _ => Status.Code.INTERNAL
    }
}
