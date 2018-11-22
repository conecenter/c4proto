package ee.cone.c4actor

import java.io.{BufferedInputStream, FileNotFoundException}
import java.net.{HttpURLConnection, URL}
import java.util.UUID

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4assemble.Single
import okio.ByteString

import scala.annotation.tailrec
import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter

case class HttpResponse(status: Int, headers: Map[String,List[String]], body: ByteString)

object HttpUtil extends LazyLogging {
  private def withConnection[T](url: String): (HttpURLConnection⇒T)⇒T =
    FinallyClose[HttpURLConnection,T](_.disconnect())(
      new URL(url).openConnection().asInstanceOf[HttpURLConnection]
    )
  private def setHeaders(connection: HttpURLConnection, headers: List[(String,String)]): Unit = {
    headers.foreach{ case (k,v) ⇒
      logger.trace(s"http header $k: $v")
      connection.setRequestProperty(k, v)
    }
  }

  def get(url: String, headers: List[(String,String)]): HttpResponse = {
    logger.debug(s"http get $url")
    val res = withConnection(url){ conn ⇒
      setHeaders(conn,headers)
      FinallyClose(new BufferedInputStream(conn.getInputStream)){ is ⇒
        FinallyClose(new okio.Buffer){ buffer ⇒
          buffer.readFrom(is)
          val headers = conn.getHeaderFields.asScala.toMap.transform{ case(k,l)⇒ l.asScala.toList }
          HttpResponse(conn.getResponseCode,headers,buffer.readByteString())
        }
      }
    }
    logger.debug(s"http get done")
    res
  }
  def post(url: String, headers: List[(String,String)]): Unit = {
    logger.debug(s"http post $url")
    withConnection(url){ conn ⇒
      conn.setRequestMethod("POST")
      setHeaders(conn, ("Content-Length", "0") :: headers)
      conn.connect()
      assert(conn.getResponseCode==200)
    }
    logger.debug(s"http post done")
  }

}

class RemoteRawSnapshotLoader(baseURL: String, authKey: AuthKey) extends RawSnapshotLoader with LazyLogging {
  def list(subDirStr: String): List[RawSnapshot] = {
    val currentTime = System.currentTimeMillis().toHexString
    val response = sendRequest(s"$baseURL/$subDirStr/$currentTime", authKey.createHash(subDirStr + currentTime))
    """(\S+)""".r.findAllIn(response.utf8())
      .toList.distinct.map(k ⇒ RawSnapshot(k))
  }
  def load(snapshot: RawSnapshot): ByteString = {
    val offsetOpt = SnapshotUtil.hashFromName(snapshot).map(_.offset)
    assert(offsetOpt.nonEmpty, s"Wrong RawSnapshot for load: ${snapshot.relativePath}")
    val offset = offsetOpt.get
    sendRequest(s"$baseURL/${snapshot.relativePath}", authKey.createHash(offset))
  }

  private def sendRequest(url: String, authKey: String): ByteString = {
    val tm = NanoTimer()
    val res = HttpUtil.get(url,("X-r-auth-key", authKey) :: Nil)
    assert(res.status==200)
    logger.debug(s"downloaded ${res.body.size} in ${tm.ms} ms")
    res.body
  }
}

class OneTimeAuthKey(hash: String) extends AuthKey {
  /**
    * Creates hash from input string using value
    */
  def createHash(addInfo: String): String = hash

  /**
    * Checks if given hash is correct for shouldAddInfo
    */
  def checkHash(shouldAddInfo: String): String ⇒ Boolean = _ == hash
}

object RemoteRawSnapshotLoaderFactory extends RawSnapshotLoaderFactory {
  def create(source: String): RawSnapshotLoader = {
    val Array(baseURL, hash, _) = source.split("#")
    new RemoteRawSnapshotLoader(baseURL, new OneTimeAuthKey(hash))
  }
}

class RemoteSnapshotMaker(appURL: String, authKey: AuthKey) extends SnapshotMaker {
  @tailrec private def retry(uuid: String): HttpResponse =
    try {
      val res = HttpUtil.get(s"$appURL/response/$uuid",Nil)
      if(res.status!=200) throw new FileNotFoundException
      res
    } catch {
      case e: FileNotFoundException ⇒
        Thread.sleep(1000)
        retry(uuid)
    }
  private def asyncPost(args: List[(String,String)]): ()⇒HttpResponse = {
    val uuid = UUID.randomUUID().toString
    HttpUtil.post(s"$appURL/need-snapshot", ("X-r-response-key",uuid) :: args)
    () ⇒ retry(uuid)
  }
  def make(task: SnapshotTask, timeHex: String): ()⇒List[RawSnapshot] = {
    val f = asyncPost(
      ("X-r-request-time", timeHex) ::
      ("X-r-auth-key", authKey.createHash(timeHex + task.offsetOpt.getOrElse("last"))) ::
        ("X-r-snapshot-mode",task.name) ::
        task.offsetOpt.toList.map(("X-r-offset",_))
    )
    () ⇒
      val headers = f().headers
      headers.getOrElse("X-r-snapshot-keys",Nil) match {
        case Seq(res) ⇒ res.split(",").map(RawSnapshot).toList
        case _ ⇒ throw new Exception(headers.getOrElse("X-r-error-message",Nil).mkString(";"))
      }
  }
}

object RemoteSnapshotMakerFactory extends SnapshotMakerFactory {
  def create(source: String): SnapshotMaker = {
    val Array(baseURL, hash, _) = source.split("#")
    new RemoteSnapshotMaker(baseURL, new OneTimeAuthKey(hash))
  }
}