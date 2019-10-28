package ee.cone.c4actor

import ee.cone.c4proto.c4
import okio._

import scala.annotation.tailrec

object NoStreamCompressorFactory extends StreamCompressorFactory {
  def create(): Option[Compressor] = None
}

@c4("ProtoApp") case class DeCompressorRegistryImpl(compressors: List[DeCompressor])(
  val byNameMap: Map[String, DeCompressor] = compressors.map(c => c.name -> c).toMap
) extends DeCompressorRegistry {
  def byName: String => DeCompressor = byNameMap
}

@c4("ServerCompApp")
case class GzipFullDeCompressor() extends DeCompressor {
  def name: String = "gzip"

  @tailrec
  private def readAgain(source: Source, sink: Buffer): Unit =
    if (source.read(sink, 10000000) >= 0)
      readAgain(source, sink)

  def deCompress(body: ByteString): ByteString =
    FinallyClose(new Buffer) { sink =>
      FinallyClose(new GzipSource(new Buffer().write(body)))(
        gzipSource =>
          readAgain(gzipSource, sink)
      )
      sink.readByteString()
    }
}

case class GzipFullCompressor() extends Compressor {
  def name: String = "gzip"
  def compress(body: ByteString): ByteString =
    FinallyClose(new Buffer) { sink =>
      FinallyClose(new GzipSink(sink))(
        gzipSink =>
          gzipSink.write(new Buffer().write(body), body.size)
      )
      sink.readByteString()
    }
}

@c4("GzipRawCompressorApp")
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


class GzipStreamCompressor extends Compressor {
  def name: String = "gzip"

  private val readSink = new Buffer()
  private val gzipSink = new GzipSink(readSink)

  def compress(body: ByteString): ByteString = synchronized {
    gzipSink.write(new Buffer().write(body), body.size)
    gzipSink.flush()
    readSink.readByteString()
  }

  // need? def close():Unit = gzipSink.close()
}

class GzipStreamCompressorFactory extends StreamCompressorFactory {
  def create(): Option[Compressor] = Option(new GzipStreamCompressor)
}