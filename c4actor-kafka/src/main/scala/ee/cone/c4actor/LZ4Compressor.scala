package ee.cone.c4actor

import net.jpountz.lz4.{LZ4BlockInputStream, LZ4BlockOutputStream}
import okio.{Buffer, ByteString}

import scala.annotation.tailrec

case object LZ4Compressor extends DeCompressor with Compressor with RawCompressor {

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

  def compress(data: ByteString): ByteString =
    FinallyClose(new Buffer) { buffer ⇒
      FinallyClose(new LZ4BlockOutputStream(buffer.outputStream(), 32000000)) { lz41 ⇒
        lz41.write(data.toByteArray)
      }
      buffer.readByteString()
    }

  def compress(data: Array[Byte]): Array[Byte] =
    FinallyClose(new Buffer) { buffer ⇒
      FinallyClose(new LZ4BlockOutputStream(buffer.outputStream(), 32000000)) { lz41 ⇒
        lz41.write(data)
      }
      buffer.readByteArray()
    }

  def name: String = "lz4"
}
