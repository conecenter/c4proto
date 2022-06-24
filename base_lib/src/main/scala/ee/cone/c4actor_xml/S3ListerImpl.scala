
package ee.cone.c4actor_xml

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.S3Lister
import ee.cone.c4di.c4

import java.nio.charset.StandardCharsets.UTF_8
import java.time.ZonedDateTime
import scala.xml.XML

@c4("S3ListerApp") final class S3ListerImpl extends S3Lister with LazyLogging {
  def parseItems(data: Array[Byte]): List[(String,String)] = {
    val xml = XML.loadString(new String(data,UTF_8))
    logger.debug(s"$xml")
    val res = for(item <- xml \ "Contents")
      yield ((item \ "Key").text, (item \ "LastModified").text)
    res.toList
  }
  def parseTime(s: String): Long = ZonedDateTime.parse(s).toInstant.toEpochMilli
}
