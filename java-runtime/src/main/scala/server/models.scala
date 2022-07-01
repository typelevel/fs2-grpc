package org.lyranthe.fs2_grpc
package java_runtime
package server

sealed abstract class ServerCompressor(val name: String) extends Product with Serializable
case object GzipCompressor extends ServerCompressor("gzip")

abstract class ServerCallOptions private (val compressor: Option[ServerCompressor], val prefetchN: Int) {
  def copy(compressor: Option[ServerCompressor] = this.compressor, prefetchN: Int = this.prefetchN): ServerCallOptions =
    new ServerCallOptions(compressor, prefetchN) {}

  def withServerCompressor(compressor: Option[ServerCompressor]): ServerCallOptions =
    copy(compressor = compressor)

  def withPrefetchN(prefetchN: Int): ServerCallOptions =
    copy(prefetchN = prefetchN)
}

object ServerCallOptions {
  val default: ServerCallOptions = new ServerCallOptions(None, 1) {}
}
