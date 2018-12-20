package org.lyranthe.fs2_grpc
package java_runtime
package server

sealed abstract class ServerCompressor(val name: String) extends Product with Serializable
case object GzipCompressor                               extends ServerCompressor("gzip")

abstract class ServerCallOptions private (val compressor: Option[ServerCompressor]) {
  def copy(compressor: Option[ServerCompressor] = this.compressor): ServerCallOptions =
    new ServerCallOptions(compressor) {}

  def withServerCompressor(compressor: Option[ServerCompressor]): ServerCallOptions =
    copy(compressor)
}

object ServerCallOptions {
  val default: ServerCallOptions = new ServerCallOptions(None) {}
}
