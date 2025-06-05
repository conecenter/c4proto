
package ee.cone.c4actor_xml

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.{S3Lister, S3Manager, TxLogName}
import ee.cone.c4di.c4

import java.nio.charset.StandardCharsets.UTF_8
import java.time.ZonedDateTime
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.XML

@c4("S3ListerApp") final class S3ListerImpl(s3: S3Manager) extends S3Lister with LazyLogging {
  def list(txLogName: TxLogName, resource: String)(implicit ec: ExecutionContext): Future[Option[List[(String,String)]]] = {
    def iter(addSearch: String, was: List[List[(String,String)]]): Future[Option[List[(String,String)]]] =
      s3.get(s3.join(txLogName,s"$resource?list-type=2$addSearch"))(ec).flatMap(
        _.fold(Future.successful(None:Option[List[(String,String)]])){ data =>
          val content = new String(data, UTF_8)
          val xml = XML.loadString(content)
          logger.debug(s"$xml")
          val will =
            (for(item <- xml \ "Contents") yield ((item \ "Key").text, (item \ "LastModified").text)).toList :: was
          val token: String = (xml \ "NextContinuationToken").text
          if(token.isEmpty) Future.successful(Option(will.reverse.flatten)) else iter(s"&continuation-token=$token", will)
        }
      )
    iter("", Nil)
  }
  def parseTime(s: String): Long = ZonedDateTime.parse(s).toInstant.toEpochMilli
}
