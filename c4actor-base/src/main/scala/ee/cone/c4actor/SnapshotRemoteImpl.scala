package ee.cone.c4actor

import java.io.FileNotFoundException
import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import java.util.UUID

import com.typesafe.scalalogging.LazyLogging
import okio.ByteString

import scala.annotation.tailrec

class SimpleSigner(fileName: String, idGenUtil : IdGenUtil)(
  val salt: String = new String(Files.readAllBytes(Paths.get(fileName)),UTF_8)
) extends Signer[List[String]] {
  def sign(data: List[String], until: Long): String = {
    val uData = until.toString :: data
    val hash = idGenUtil.srcIdFromStrings(salt :: uData:_*)
    (hash :: uData).map(URLEncoder.encode(_,"UTF-8")).mkString("=")
  }

  def retrieve(check: Boolean): Option[String]⇒Option[List[String]] = _.flatMap{ signed ⇒
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

object RemoteRawSnapshotLoaderFactory extends RawSnapshotLoaderFactory {
  def create(baseURL: String): RawSnapshotLoader =
    new RemoteRawSnapshotLoader(baseURL)
}

class SnapshotTaskSigner(inner: Signer[List[String]])(
  val url: String = "/need-snapshot"
) extends Signer[SnapshotTask] {
  def sign(task: SnapshotTask, until: Long): String = inner.sign(List(url,task.name) ++ task.offsetOpt, until)
  def retrieve(check: Boolean): Option[String]⇒Option[SnapshotTask] =
    signed ⇒ inner.retrieve(check)(signed) match {
      case Some(Seq(`url`,"next")) ⇒ Option(NextSnapshotTask(None))
      case Some(Seq(`url`,"next", offset)) ⇒ Option(NextSnapshotTask(Option(offset)))
      case Some(Seq(`url`,"debug", offset)) ⇒ Option(DebugSnapshotTask(offset))
      case _ ⇒ None
    }
}

class RemoteSnapshotUtilImpl extends RemoteSnapshotUtil {
  def request(appURL: String, signed: String): ()⇒List[RawSnapshot] = {
    val url: String = "/need-snapshot"
    val uuid = UUID.randomUUID().toString
    HttpUtil.post(s"$appURL$url", ("x-r-response-key",uuid) :: HttpUtil.authHeaders(signed))
    () ⇒
      @tailrec def retry(): HttpResponse =
        try {
          val res = HttpUtil.get(s"$appURL/response/$uuid",Nil)
          if(res.status!=200) throw new FileNotFoundException
          res
        } catch {
          case e: FileNotFoundException ⇒
            Thread.sleep(1000)
            retry()
        }
      val headers = retry().headers
      headers.getOrElse("x-r-snapshot-keys",Nil) match {
        case Seq(res) ⇒ res.split(",").map(RawSnapshot).toList
        case _ ⇒ throw new Exception(headers.getOrElse("x-r-error-message",Nil).mkString(";"))
      }
  }
}

class RemoteSnapshotMaker(
  appURL: String, util: RemoteSnapshotUtil, signer: Signer[SnapshotTask]
) extends SnapshotMaker {
  def make(task: SnapshotTask): List[RawSnapshot] =
    util.request(appURL, signer.sign(task, System.currentTimeMillis() + 3600*1000))()
}
