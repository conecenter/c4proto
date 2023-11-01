package ee.cone.c4actor

import okio.BufferedSource

sealed trait NamedCompressing {
  def name: String
}

trait DeCompressor extends NamedCompressing {
  def deCompress(compressed: BufferedSource): BufferedSource
}

trait RawCompressor extends NamedCompressing {
  def compress(data: Array[Byte]): Array[Byte]
}

trait StreamCompressorFactory {
  def create(): Option[RawCompressor]
}
