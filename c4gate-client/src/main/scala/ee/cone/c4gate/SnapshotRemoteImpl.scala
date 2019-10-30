package ee.cone.c4gate

import java.io.FileNotFoundException
import java.util.UUID

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.{Config, NanoTimer, RawSnapshot, RawSnapshotLoader, RawSnapshotLoaderFactory, RemoteSnapshotUtil, SnapshotMaker, SnapshotTask, SnapshotTaskSigner}
import ee.cone.c4proto.{c4, provide}
import okio.ByteString

import scala.annotation.tailrec

class RemoteRawSnapshotLoader(baseURL: String, util: HttpUtil) extends RawSnapshotLoader with LazyLogging {
  def load(snapshot: RawSnapshot): ByteString = {
    val tm = NanoTimer()
    val resp = util.get(s"$baseURL/${snapshot.relativePath}", Nil)
    assert(resp.status == 200)
    logger.debug(s"downloaded ${resp.body.size} in ${tm.ms} ms")
    resp.body
  }
}

@c4("MergingSnapshotApp") class RemoteRawSnapshotLoaderFactory(util: HttpUtil) extends RawSnapshotLoaderFactory {
  def create(baseURL: String): RawSnapshotLoader =
    new RemoteRawSnapshotLoader(baseURL,util)
}

@c4("RemoteRawSnapshotApp") class RemoteSnapshotUtilImpl(util: HttpUtil) extends RemoteSnapshotUtil {
  def authHeaders(signed: String): List[(String, String)] =
    List(("x-r-signed", signed))
  def request(appURL: String, signed: String): ()=>List[RawSnapshot] = {
    val url: String = "/need-snapshot"
    val uuid = UUID.randomUUID().toString
    util.post(s"$appURL$url", ("x-r-response-key",uuid) :: authHeaders(signed))
    () =>
      @tailrec def retry(): HttpResponse =
        try {
          val res = util.get(s"$appURL/response/$uuid",Nil)
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

@c4("RemoteRawSnapshotApp") class EnvRemoteRawSnapshotLoader(url: RemoteSnapshotAppURL, util: HttpUtil) {
  @provide def get: Seq[RawSnapshotLoader] = List(new RemoteRawSnapshotLoader(url.value,util))
}

@c4("RemoteRawSnapshotApp") class RemoteSnapshotMaker(
  appURL: RemoteSnapshotAppURL, util: RemoteSnapshotUtil, signer: SnapshotTaskSigner
) extends SnapshotMaker {
  def make(task: SnapshotTask): List[RawSnapshot] =
    util.request(appURL.value, signer.sign(task, System.currentTimeMillis() + 3600*1000))()
}
