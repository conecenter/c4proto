package ee.cone.c4actor

import ee.cone.c4proto.c4component
import net.jpountz.lz4.{LZ4BlockInputStream, LZ4BlockOutputStream}
import okio.{Buffer, ByteString}

import scala.annotation.tailrec

@c4component("LZ4DeCompressorApp")
case class LZ4DeCompressor() extends DeCompressor {
  def name: String = "lz4"
  @tailrec
  private def readAgain(in: LZ4BlockInputStream, sink: Buffer): Unit = {
    val size = in.available()
    val byteArray = new Array[Byte](size)
    if (in.read(byteArray) >= 0) {
      sink.write(byteArray)
      readAgain(in, sink)
    }
  }

  def deCompress(data: ByteString): ByteString =
    FinallyClose(new Buffer) { buffer ⇒
      FinallyClose(new LZ4BlockInputStream(new Buffer().write(data).inputStream())) { lz41 ⇒
        readAgain(lz41, buffer)
      }
      buffer.readByteString()
    }
}

@c4component("LZ4RawCompressorApp")
case class LZ4RawCompressor() extends RawCompressor {
  def name: String = "lz4"
  def compress(data: Array[Byte]): Array[Byte] =
    FinallyClose(new Buffer) { buffer ⇒
      FinallyClose(new LZ4BlockOutputStream(buffer.outputStream(), 32000000)) { lz41 ⇒
        lz41.write(data)
      }
      buffer.readByteArray()
    }
}

case class LZ4Compressor() extends Compressor {
  def name: String = "lz4"
  def compress(data: ByteString): ByteString =
    FinallyClose(new Buffer) { buffer ⇒
      FinallyClose(new LZ4BlockOutputStream(buffer.outputStream(), 32000000)) { lz41 ⇒
        lz41.write(data.toByteArray)
      }
      buffer.readByteString()
    }
}
