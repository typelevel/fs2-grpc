package org.lyranthe.fs2_grpc
package java_runtime
package server

sealed abstract class ServerCompressor(val name: String) extends Product with Serializable
case object GzipCompressor                               extends ServerCompressor("gzip")

final case class ServerCallOptions(compressor: Option[ServerCompressor])

object ServerCallOptions {
  def empty: ServerCallOptions = ServerCallOptions(compressor = None)
}
