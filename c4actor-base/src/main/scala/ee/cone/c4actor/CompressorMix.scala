package ee.cone.c4actor

trait GzipCompressorApp {
  lazy val compressor: Compressor = GzipCompressor()
}

trait NoMessageCompressionApp {
  lazy val messageCompressor: Compressor = NoCompression
}

trait GZipMessageCompressionApp {
  lazy val messageCompressor: Compressor = GzipCompressor()
}

trait WithGZipCompressorApp extends CompressorsApp {
  override def compressors: List[Compressor] = GzipCompressor() :: super.compressors
}

trait WithCopyCompressorApp extends CompressorsApp {
  override def compressors: List[Compressor] = NoCompression :: super.compressors
}

trait CompressorRegistryMix extends CompressorsApp {
  lazy val compressorRegistry: CompressorRegistry = CompressorRegistryImpl(compressors, NoCompression)
}