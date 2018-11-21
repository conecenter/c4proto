package ee.cone.c4actor

import java.nio.charset.StandardCharsets.UTF_8

import ee.cone.c4proto.ToByteString
import okio._

import scala.annotation.tailrec

object NoCompressorFactory extends CompressorFactory {
  def create(): Option[Compressor] = None
}

case class CompressorRegistryImpl(compressors: List[Compressor]) extends CompressorRegistry {
  lazy val byNameMap: Map[String, Compressor] = compressors.map(c ⇒ c.name → c).toMap

  def byName: String => Compressor = byNameMap.getOrElse(_, CopyCompressor)

  lazy val byByteMap: Map[ByteString, Compressor] = compressors.map(c ⇒ ToByteString(c.name.getBytes(UTF_8)) → c).toMap

  def byByte: ByteString ⇒ Compressor = byByteMap.getOrElse(_, CopyCompressor)
}

case object CopyCompressor extends Compressor {
  def name: String = ""

  def compress: ByteString ⇒ ByteString = identity

  def deCompress: ByteString ⇒ ByteString = identity

  def compressRaw: Array[Byte] => Array[Byte] = identity

  def getKafkaHeaders: List[KafkaHeader] = Nil

  def getRawHeaders: List[RawHeader] = Nil
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
    if (source.read(sink, 1000000) >= 0)
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

  def getKafkaHeaders: List[KafkaHeader] = List(CompressedKafkaHeader(name))

  def getRawHeaders: List[RawHeader] = List(CompressedKafkaHeader(name))
}

case class CompressedKafkaHeader(name: String) extends KafkaHeader with RawHeader {
  def key: String = "compressor"

  val value: Array[Byte] = name.getBytes(UTF_8)

  def data: ByteString = ToByteString(value)
}

class GzipCompressorStream extends Compressor {
  def name: String = "gzip"

  private val readSink = new Buffer()
  private val gzipSink = new GzipSink(readSink)

  def compress: ByteString ⇒ ByteString = body ⇒ synchronized {
    gzipSink.write(new Buffer().write(body), body.size)
    gzipSink.flush()
    readSink.readByteString()
  }

  // def close():Unit = gzipSink.close()
  def deCompress: ByteString => ByteString = body ⇒
    FinallyClose(new Buffer) { sink ⇒
      FinallyClose(new GzipSource(new Buffer().write(body)))(
        gzipSink ⇒
          gzipSink.read(sink, body.size)
      )
      sink.readByteString()
    }

  def compressRaw: Array[Byte] => Array[Byte] = body ⇒ synchronized {
    gzipSink.write(new Buffer().write(body), body.length)
    gzipSink.flush()
    readSink.readByteArray()
  }

  def getKafkaHeaders: List[KafkaHeader] = List(CompressedKafkaHeader(name))

  def getRawHeaders: List[RawHeader] = List(CompressedKafkaHeader(name))
}

class GzipGzipCompressorStreamFactory extends CompressorFactory {
  def create(): Option[Compressor] = Option(new GzipCompressorStream)
}