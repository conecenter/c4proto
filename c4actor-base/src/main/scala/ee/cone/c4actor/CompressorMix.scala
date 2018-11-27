package ee.cone.c4actor

trait DeCompressorsApp {
  def deCompressors: List[DeCompressor] = Nil
}

trait RawCompressorsApp {
  def rawCompressors: List[RawCompressor] = Nil
}

trait GzipRawCompressorApp extends RawCompressorsApp {
  override def rawCompressors: List[RawCompressor] =
    GzipFullCompressor() :: super.rawCompressors
}
