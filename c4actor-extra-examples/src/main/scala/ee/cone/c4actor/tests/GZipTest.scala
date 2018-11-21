package ee.cone.c4actor.tests

import ee.cone.c4actor.{GzipCompressor, GzipCompressorStream}
import ee.cone.c4proto.ToByteString
import java.nio.charset.StandardCharsets.UTF_8

object GZipTest {
 val compressor = GzipCompressor()

  def main(args: Array[String]): Unit = {
    val preTest = "test ololololasdasdaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaafaaaaaaaaaaaaaaaaadgggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggssssssssssssssssssssssssssssssssssssssssssssssssssssssss4444444444444444444444444444444444444444444444444sdfsdfsdfdsaaaas"
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
    println(preString1, new String(compressed1.toByteArray, UTF_8),result1)
  }
}
