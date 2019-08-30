package ee.cone.c4actor

import okio.ByteString

sealed trait NamedCompressing {
  def name: String
}

trait Compressor extends NamedCompressing {
  def compress(data: ByteString): ByteString
}

trait DeCompressor extends NamedCompressing {
  def deCompress(data: ByteString): ByteString
}

trait RawCompressor extends NamedCompressing {
  def compress(data: Array[Byte]): Array[Byte]
}

trait DeCompressorRegistry {
  def byName: String ⇒ DeCompressor
}

trait StreamCompressorFactory {
  def create(): Option[Compressor]
}
