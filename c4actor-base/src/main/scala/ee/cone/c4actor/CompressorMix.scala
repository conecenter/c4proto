package ee.cone.c4actor

trait GzipCompressorApp {
  lazy val compressor: Compressor = GzipCompressor()
}
