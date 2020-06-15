package ee.cone.c4actor.tests

import java.io.InputStream

import ee.cone.c4actor._
import ee.cone.c4proto.ToByteString
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import java.util.zip._
import okio.{Buffer, ByteString}

import scala.annotation.tailrec
import scala.collection.JavaConverters.iterableAsScalaIterableConverter

object GZipTest {
  val compressor = GzipFullCompressor()
  val decompressor = GzipFullDeCompressor()

  def main(args: Array[String]): Unit = {
    /*val preTest = "test ololololasdasdaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaafaaaaaaaaaaaaaaaaadgggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggssssssssssssssssssssssssssssssssssssssssssssssssssssssss4444444444444444444444444444444444444444444444444sdfsdfsdfdsaaaas"
    val preString = preTest*10000
    val bytes = ToByteString(preString.getBytes(UTF_8))
    val compressed = compressor.compress(bytes)
    val decompressed = compressor.deCompress(compressed)
    val result = new String(decompressed.toByteArray, UTF_8)
    println(preString == result, new String(compressed.toByteArray, UTF_8))
    val preString1 = "111111111111111111111111111111111111111111111111111111111111111111111"
    val bytes1 = ToByteString(preString1.getBytes(UTF_8))
    val compressed1 = compressor.compress(bytes1)
    val decompressed1 = compressor.deCompress(compressed1)
    val result1 = new String(decompressed1.toByteArray, UTF_8)
    println(preString1, new String(compressed1.toByteArray, UTF_8),result1)*/
    val bPath = ""
    val baseDir = Paths.get(bPath)
    val subDirStr = ""
    val subDir = baseDir
    //.resolve(subDirStr)
    /*println(FinallyClose(Files.newDirectoryStream(subDir))(_.asScala.toList)
      .map(path=>baseDir.relativize(path).toString))*/
    val path = baseDir.resolve("0000000000000000-b00538a7-8c57-3225-bb30-7fae7dd107c0")
    val byteStr = TimeColored("g", "read")(ToByteString(Files.readAllBytes(path)))
    println(byteStr.size)
    System.gc()
    val decomp = TimeColored("g", "comp")(compressor.compress(byteStr))
    println(decomp.size)
    val comp = TimeColored("g", "decomp")(decompressor.deCompress(decomp))
    println(comp.size)
    System.gc()

    val decomp2 = TimeColored("g", "comp")(compressor.compress(byteStr))
    println(decomp2.size)
    val comp2 = TimeColored("g", "decomp")(decompressor.deCompress(decomp))
    println(comp2.size)

    println("Valid?", byteStr == comp)
  }
}

case class DeflaterCompressor(level: Int = 1) extends DeCompressor with Compressor with RawCompressor {

  private def ignoreTheSameBuffer(value: Buffer): Unit = ()

  @tailrec
  private def readAgain(in: InflaterInputStream, sink: Buffer, offset: Int = 0): Unit = {
    val size = 32000000
    val byteArray = new Array[Byte](size)
    val read = in.read(byteArray)
    if (read >= 0) {
      ignoreTheSameBuffer(sink.write(byteArray, 0, read))
      readAgain(in, sink, offset + read)
    }
  }

  def deCompress(data: ByteString): ByteString =
    FinallyClose(new Buffer) { buffer =>
      FinallyClose(new InflaterInputStream(new Buffer().write(data).inputStream(), new Inflater(true), 10000000)) { deflater =>
          readAgain(deflater, buffer)
      }
      buffer.readByteString()
    }

  def compress(data: ByteString): ByteString =
    FinallyClose(new Buffer) { buffer =>
      FinallyClose(new DeflaterOutputStream(buffer.outputStream(), new Deflater(level, true), 10000000)) { deflater =>
        deflater.write(data.toByteArray)
      }
      buffer.readByteString()
    }

  def compress(data: Array[Byte]): Array[Byte] =
    FinallyClose(new Buffer) { buffer =>
        FinallyClose(new DeflaterOutputStream(buffer.outputStream(), new Deflater(level, true), 10000000)) { defl =>
          defl.write(data)
        }
      buffer.readByteArray()
    }

  def name: String = "DefJ"
}
