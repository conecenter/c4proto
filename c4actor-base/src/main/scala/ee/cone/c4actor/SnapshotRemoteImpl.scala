package ee.cone.c4actor

import java.io.BufferedInputStream
import java.net.{HttpURLConnection, URL}
import java.util.UUID

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4assemble.Single
import okio.ByteString

import scala.annotation.tailrec

import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter

case class HttpResponse(status: Int, headers: Map[String,List[String]], body: ByteString)

object HttpUtil {
  private def withConnection[T](url: String): (HttpURLConnection⇒T)⇒T =
    FinallyClose[HttpURLConnection,T](_.disconnect())(
      new URL(url).openConnection().asInstanceOf[HttpURLConnection]
    )

  def get(url: String): HttpResponse = withConnection(url){ conn ⇒
    FinallyClose(new BufferedInputStream(conn.getInputStream)){ is ⇒
      FinallyClose(new okio.Buffer){ buffer ⇒
        buffer.readFrom(is)
        val headers = conn.getHeaderFields.asScala.toMap.transform{ case(k,l)⇒ l.asScala.toList }
        HttpResponse(conn.getResponseCode,headers,buffer.readByteString())
      }
    }
  }
  def post(url: String, headers: List[(String,String)]): Unit =
    withConnection(url){ conn ⇒
      conn.setRequestMethod("POST")
      val allHeaders = ("Content-Length", "0") :: headers
      allHeaders.foreach{ case (k,v) ⇒ conn.setRequestProperty(k, v) }
      conn.connect()
      assert(conn.getResponseCode==200)
    }
}

class RemoteRawSnapshotLoader(baseURL: String) extends RawSnapshotLoader with LazyLogging {
  def list(subDirStr: String): List[RawSnapshot] = {
    val url = s"$baseURL/$subDirStr/"
    val res = HttpUtil.get(url)
    assert(res.status==200)
    """([0-9a-f\-]+)""".r.findAllIn(res.body.utf8())
      .toList.distinct.map(name⇒RawSnapshot(s"$url$name"))
  }
  def load(snapshot: RawSnapshot): ByteString = {
    val tm = NanoTimer()
    val res = HttpUtil.get(snapshot.key)
    assert(res.status==200)
    logger.info(s"downloaded ${res.body.size} in ${tm.ms} ms")
    res.body
  }
}

class RemoteSnapshotMaker(appURL: String) extends SnapshotMaker {
  @tailrec private def retry(uuid: String): HttpResponse = {
    val res = HttpUtil.get(s"$appURL/response/$uuid")
    if(res.status==200) res else {
      Thread.sleep(1000)
      retry(uuid)
    }
  }
  private def asyncPost(args: List[(String,String)], resultKey: String): ()⇒String = {
    val uuid = UUID.randomUUID().toString
    HttpUtil.post(s"$appURL/need-snapshot", ("X-r-response-key",uuid) :: args)
    () ⇒
      val resp = retry(uuid)
      resp.headers.getOrElse("X-r-error-message",Nil).foreach(m⇒throw new Exception(m))
      Single(resp.headers.getOrElse(resultKey,Nil))
  }
  def make(task: SnapshotTask): ()⇒List[RawSnapshot] = {
    val f = asyncPost(
      ("X-r-snapshot-mode",task.name) :: task.offsetOpt.toList.map(("X-r-offset",_)),
      "X-r-snapshot-keys"
    )
    () ⇒ f().split(",").map(RawSnapshot).toList
  }
}

