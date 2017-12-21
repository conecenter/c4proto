package ee.cone.c4actor

import okio.ByteString

trait Compressor {
  def name: String
  def compress: ByteString ⇒ ByteString
}
