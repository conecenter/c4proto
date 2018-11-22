package ee.cone.c4actor

import okio._

import scala.annotation.tailrec

object NoJustCompressorFactory extends JustCompressorFactory {
  def create(): Option[JustCompressor] = None
}

case class CompressorRegistryImpl(compressors: List[Compressor]) extends CompressorRegistry {
  lazy val byNameMap: Map[String, Compressor] = compressors.map(c ⇒ c.name → c).toMap

  def byName: String ⇒ Compressor = byNameMap
}

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

  @tailrec
  private def readAgain(source: Source, sink: Buffer): Unit =
    if (source.read(sink, 10000000) >= 0)
      readAgain(source, sink)

  def deCompress: ByteString ⇒ ByteString = body ⇒
    FinallyClose(new Buffer) { sink ⇒
      FinallyClose(new GzipSource(new Buffer().write(body)))(
        gzipSource ⇒
          readAgain(gzipSource, sink)
      )
      sink.readByteString()
    }

  def compressRaw: Array[Byte] ⇒ Array[Byte] = body ⇒
    FinallyClose(new Buffer) { sink ⇒
      FinallyClose(new GzipSink(sink))(
        gzipSink ⇒
          gzipSink.write(new Buffer().write(body), body.length)
      )
      sink.readByteArray()
    }
}

class GzipJustCompressorStream extends JustCompressor {
  def name: String = "gzip"

  private val readSink = new Buffer()
  private val gzipSink = new GzipSink(readSink)

  def compress: ByteString ⇒ ByteString = body ⇒ synchronized {
    gzipSink.write(new Buffer().write(body), body.size)
    gzipSink.flush()
    readSink.readByteString()
  }
}

class GzipGzipJustCompressorStreamFactory extends JustCompressorFactory {
  def create(): Option[JustCompressor] = Option(new GzipJustCompressorStream)
}