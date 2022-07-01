package org.lyranthe.fs2_grpc.java_runtime.server

import io.grpc.{Metadata, MethodDescriptor, ServerCall, Status}

import scala.collection.mutable.ArrayBuffer

class DummyServerCall extends ServerCall[String, Int] {
  val messages: ArrayBuffer[Int] = ArrayBuffer[Int]()
  var currentStatus: Option[Status] = None
  private var ready = true

  override def request(numMessages: Int): Unit = ()
  override def sendMessage(message: Int): Unit = {
    messages += message
    ()
  }
  override def sendHeaders(headers: Metadata): Unit = {
    ()
  }
  override def getMethodDescriptor: MethodDescriptor[String, Int] = ???
  override def close(status: Status, trailers: Metadata): Unit = {
    currentStatus = Some(status)
  }
  override def isCancelled: Boolean = false

  override def isReady: Boolean = ready

  def setIsReady(value: Boolean, listener: ServerCall.Listener[_]): Unit = {
    ready = value
    if (ready) {
      listener.onReady()
    }
  }
}
