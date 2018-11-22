package ee.cone.c4actor

import okio.ByteString

trait JustCompressor {
  def name: String

  def compress: ByteString ⇒ ByteString
}

trait JustDeCompressor {
  def name: String

  def deCompress: ByteString ⇒ ByteString
}

trait Compressor extends JustCompressor with JustDeCompressor {
  def compressRaw: Array[Byte] ⇒ Array[Byte]
}

trait CompressionApp {
  def compressors: List[Compressor] = Nil
}

trait CompressorsRegistryApp {
  def compressorRegistry: CompressorRegistry
}

trait CompressorRegistry {
  def byName: String ⇒ Compressor
}

trait JustCompressorFactory {
  def create(): Option[JustCompressor]
}
