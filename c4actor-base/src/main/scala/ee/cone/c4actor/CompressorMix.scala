package ee.cone.c4actor

trait GzipCompressorApp {
  lazy val compressor: JustCompressor = GzipCompressor()
}

trait WithGZipCompressorApp extends CompressionApp {
  override def compressors: List[Compressor] = GzipCompressor() :: super.compressors
}

trait CompressorRegistryMix extends CompressionApp {
  lazy val compressorRegistry: CompressorRegistry = CompressorRegistryImpl(compressors)
}