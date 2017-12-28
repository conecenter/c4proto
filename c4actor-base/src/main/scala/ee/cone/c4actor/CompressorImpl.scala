package ee.cone.c4actor

import okio.{Buffer, ByteString, GzipSink}

case class GzipCompressor() extends Compressor {
  def name: String = "gzip"
  def compress: ByteString ⇒ ByteString = body ⇒
    FinallyClose(new Buffer) { sink ⇒
      FinallyClose(new GzipSink(sink))(
        gzipSink ⇒
          gzipSink.write(new Buffer().write(body), body.size)
      )
      sink.readByteString()
    }
}
