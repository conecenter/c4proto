
package ee.cone.c4gate_server

import java.nio.charset.StandardCharsets.UTF_8
import java.time.ZonedDateTime
import scala.xml.XML
import com.typesafe.scalalogging.LazyLogging

import ee.cone.c4di.c4

@c4("S3RawSnapshotLoaderApp") final class S3ListerImpl extends S3Lister with LazyLogging {
  def parseItems(data: Array[Byte]): List[(String,String)] = {
    val xml = XML.loadString(new String(data,UTF_8))
    logger.debug(s"$xml")
    val res = for(item <- xml \ "Contents")
      yield ((item \ "Key").text, (item \ "LastModified").text)
    res.toList
  }
  def parseTime(s: String): Long = ZonedDateTime.parse(s).toInstant.toEpochMilli
}
