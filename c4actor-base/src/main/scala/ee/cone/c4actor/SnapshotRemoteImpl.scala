package ee.cone.c4actor

import java.io.{BufferedInputStream, FileNotFoundException}
import java.net.{HttpURLConnection, URL, URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import java.util.{Locale, UUID}

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4proto.c4
import okio.ByteString

import scala.annotation.tailrec
// import scala.collection.JavaConverters.mapAsScalaMapConverter
// import scala.collection.JavaConverters.iterableAsScalaIterableConverter
// import scala.jdk.CollectionConverters.MapHasAsScala


case class HttpResponse(status: Int, headers: Map[String,List[String]], body: ByteString)

object HttpUtil extends LazyLogging {
  private def withConnection[T](url: String): (HttpURLConnection=>T)=>T =
    FinallyClose[HttpURLConnection,T](_.disconnect())(
      new URL(url).openConnection().asInstanceOf[HttpURLConnection]
    )
  private def setHeaders(connection: HttpURLConnection, headers: List[(String,String)]): Unit = {
    headers.flatMap(normalizeHeader).foreach{ case (k,v) =>
      logger.trace(s"http header $k: $v")
      connection.setRequestProperty(k, v)
    }
  }
  private def normalizeHeader[T](kv: (String,T)): List[(String,T)] = {
    val (k,v) = kv
    Option(k).map(k=>(k.toLowerCase(Locale.ENGLISH), v)).toList
  }

  def get(url: String, headers: List[(String,String)]): HttpResponse = {
    logger.debug(s"http get $url")
    val res = withConnection(url){ conn =>
      setHeaders(conn,headers)
      FinallyClose(new BufferedInputStream(conn.getInputStream)){ is =>
        FinallyClose(new okio.Buffer){ buffer =>
          buffer.readFrom(is)
          import scala.jdk.CollectionConverters._
          val headers = conn.getHeaderFields.asScala.map{
            case (k,l) => k -> l.asScala.toList
          }.flatMap(normalizeHeader).toMap
          HttpResponse(conn.getResponseCode,headers,buffer.readByteString())
        }
      }
    }
    logger.debug(s"http get done")
    res
  }
  def post(url: String, headers: List[(String,String)]): Unit = {
    logger.debug(s"http post $url")
    withConnection(url){ conn =>
      conn.setRequestMethod("POST")
      setHeaders(conn, ("content-length", "0") :: headers)
      conn.connect()
      logger.debug(s"http resp status ${conn.getResponseCode}")
      assert(conn.getResponseCode==200)
    }
    logger.debug(s"http post done")
  }
  def authHeaders(signed: String): List[(String,String)] =
    List(("x-r-signed", signed))
  def ok(res: HttpResponse): ByteString = {
    assert(res.status==200)
    res.body
  }
}

@c4("ConfigSimpleSignerApp") class SimpleSigner(
  config: Config, idGenUtil : IdGenUtil
)(
  fileName: String = config.get("C4AUTH_KEY_FILE")
)(
  val salt: String = new String(Files.readAllBytes(Paths.get(fileName)),UTF_8)
) extends Signer[List[String]] {
  def sign(data: List[String], until: Long): String = {
    val uData = until.toString :: data
    val hash = idGenUtil.srcIdFromStrings(salt :: uData:_*)
    (hash :: uData).map(URLEncoder.encode(_,"UTF-8")).mkString("=")
  }

  def retrieve(check: Boolean): Option[String]=>Option[List[String]] = _.flatMap{ signed =>
    val hash :: untilStr :: data = signed.split("=").map(URLDecoder.decode(_,"UTF-8")).toList
    val until = untilStr.toLong
    if(!check) Option(data)
    else if(until < System.currentTimeMillis) None
    else if(sign(data,until) == signed) Option(data)
    else None
  }
}

class RemoteRawSnapshotLoader(baseURL: String) extends RawSnapshotLoader with LazyLogging {
  def load(snapshot: RawSnapshot): ByteString = {
    val tm = NanoTimer()
    val res = HttpUtil.ok(HttpUtil.get(s"$baseURL/${snapshot.relativePath}", Nil))
    logger.debug(s"downloaded ${res.size} in ${tm.ms} ms")
    res
  }
}

@c4("MergingSnapshotApp") class RemoteRawSnapshotLoaderFactory extends RawSnapshotLoaderFactory {
  def create(baseURL: String): RawSnapshotLoader =
    new RemoteRawSnapshotLoader(baseURL)
}

@c4("TaskSignerApp") class SnapshotTaskSignerImpl(inner: Signer[List[String]])(
  val url: String = "/need-snapshot"
) extends SnapshotTaskSigner {
  def sign(task: SnapshotTask, until: Long): String = inner.sign(List(url,task.name) ++ task.offsetOpt, until)
  def retrieve(check: Boolean): Option[String]=>Option[SnapshotTask] =
    signed => inner.retrieve(check)(signed) match {
      case Some(Seq(`url`,"next")) => Option(NextSnapshotTask(None))
      case Some(Seq(`url`,"next", offset)) => Option(NextSnapshotTask(Option(offset)))
      case Some(Seq(`url`,"debug", offset)) => Option(DebugSnapshotTask(offset))
      case _ => None
    }
}

@c4("RemoteRawSnapshotApp") class RemoteSnapshotUtilImpl extends RemoteSnapshotUtil {
  def request(appURL: String, signed: String): ()=>List[RawSnapshot] = {
    val url: String = "/need-snapshot"
    val uuid = UUID.randomUUID().toString
    HttpUtil.post(s"$appURL$url", ("x-r-response-key",uuid) :: HttpUtil.authHeaders(signed))
    () =>
      @tailrec def retry(): HttpResponse =
        try {
          val res = HttpUtil.get(s"$appURL/response/$uuid",Nil)
          if(res.status!=200) throw new FileNotFoundException
          res
        } catch {
          case e: FileNotFoundException =>
            Thread.sleep(1000)
            retry()
        }
      val headers = retry().headers
      headers.getOrElse("x-r-snapshot-keys",Nil) match {
        case Seq(res) => res.split(",").map(RawSnapshot).toList
        case _ => throw new Exception(headers.getOrElse("x-r-error-message",Nil).mkString(";"))
      }
  }
}

class RemoteSnapshotAppURL(val value: String)

@c4("RemoteRawSnapshotApp") class DefRemoteSnapshotAppURL(config: Config) extends RemoteSnapshotAppURL(config.get("C4HTTP_SERVER"))

@c4("RemoteRawSnapshotApp") class EnvRemoteRawSnapshotLoader(url: RemoteSnapshotAppURL) extends RemoteRawSnapshotLoader(url.value)

@c4("RemoteRawSnapshotApp") class RemoteSnapshotMaker(
  appURL: RemoteSnapshotAppURL, util: RemoteSnapshotUtil, signer: SnapshotTaskSigner
) extends SnapshotMaker {
  def make(task: SnapshotTask): List[RawSnapshot] =
    util.request(appURL.value, signer.sign(task, System.currentTimeMillis() + 3600*1000))()
}
