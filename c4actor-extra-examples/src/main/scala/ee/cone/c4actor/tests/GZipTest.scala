package ee.cone.c4actor.tests

import ee.cone.c4actor._
import ee.cone.c4proto.ToByteString
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}

import scala.collection.JavaConverters.iterableAsScalaIterableConverter

object GZipTest {
 val compressor = GzipFullCompressor()

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
    val subDir = baseDir//.resolve(subDirStr)
    /*println(FinallyClose(Files.newDirectoryStream(subDir))(_.asScala.toList)
      .map(path⇒baseDir.relativize(path).toString))*/
    val path = baseDir.resolve("000000000000a350-368ebdc6-e82e-3bc7-81a8-79846f2bec91-gzip")
    val byteStr = TimeColored("g", "read")(ToByteString(Files.readAllBytes(path)))
    println(byteStr.size)
    val decomp = TimeColored("g", "decomp")(compressor.deCompress(byteStr))
    println(decomp.size)
    val comp = TimeColored("g", "comp")(compressor.compress(decomp))
    println(comp.size)
  }
}
