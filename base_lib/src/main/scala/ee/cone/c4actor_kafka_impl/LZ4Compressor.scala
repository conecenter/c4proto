package ee.cone.c4actor_kafka_impl

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor._
import ee.cone.c4di.c4
import net.jpountz.lz4.{LZ4BlockInputStream, LZ4BlockOutputStream}
import okio.{Buffer, BufferedSource, Okio, RealBufferedSource}

@c4("LZ4DeCompressorApp") final 
case class LZ4DeCompressor() extends DeCompressor with LazyLogging {
  def name: String = "lz4"
  def deCompress(compressed: BufferedSource): BufferedSource =
    new RealBufferedSource(Okio.source(new LZ4BlockInputStream(compressed.inputStream())))
}

@c4("LZ4RawCompressorApp") final 
case class LZ4RawCompressor() extends RawCompressor {
  def name: String = "lz4"
  def compress(data: Array[Byte]): Array[Byte] =
    FinallyClose(new Buffer) { buffer =>
      FinallyClose(new LZ4BlockOutputStream(buffer.outputStream(), 32000000)) { lz41 =>
        lz41.write(data)
      }
      buffer.readByteArray()
    }
}
