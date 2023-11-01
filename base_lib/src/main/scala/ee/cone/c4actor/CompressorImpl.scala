package ee.cone.c4actor

import ee.cone.c4di.c4
import okio._

@c4("ServerCompApp") final 
case class GzipFullDeCompressor() extends DeCompressor {
  def name: String = "gzip"
  def deCompress(compressed: BufferedSource): BufferedSource = new RealBufferedSource(new GzipSource(compressed))
}

@c4("GzipRawCompressorApp") final 
case class GzipFullRawCompressor() extends RawCompressor {
  def name: String = "gzip"
  def compress(body: Array[Byte]): Array[Byte] =
    FinallyClose(new Buffer) { sink =>
      FinallyClose(new GzipSink(sink))(
        gzipSink =>
          gzipSink.write(new Buffer().write(body), body.length)
      )
      sink.readByteArray()
    }
}


class GzipStreamCompressor(
  readSink: Buffer = new Buffer()
)(
  gzipSink: GzipSink = new GzipSink(readSink)
) extends RawCompressor {
  def name: String = "gzip"

  def compress(body: Array[Byte]): Array[Byte] = synchronized {
    gzipSink.write(new Buffer().write(body), body.length)
    gzipSink.flush()
    readSink.readByteArray()
  }

  // need? def close():Unit = gzipSink.close()
}

class GzipStreamCompressorFactory extends StreamCompressorFactory {
  def create(): Option[RawCompressor] = Option(new GzipStreamCompressor()())
}