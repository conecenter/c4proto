package ee.cone.c4actor

import okio.ByteString

case object CurrentCompressorKey extends TransientLens[Option[Compressor]](None)

trait Compressor {
  def name: String

  def compressRaw: Array[Byte] ⇒ Array[Byte]

  def compress: ByteString ⇒ ByteString

  def deCompress: ByteString ⇒ ByteString

  def getKafkaHeaders: List[KafkaHeader]

  def getRawHeaders: List[RawHeader]
}

trait CompressorsApp {
  def compressors: List[Compressor] = Nil
}

trait CompressorsRegistryApp {
  def compressorRegistry: CompressorRegistry
}

trait CompressorRegistry {
  def byName: String ⇒ Compressor
  def byByte: ByteString ⇒ Compressor
}

trait CompressorFactory {
  def create(): Option[Compressor]
}
