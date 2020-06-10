package ee.cone.c4gate

import java.io.FileNotFoundException
import java.util.UUID

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.{Config, NanoTimer, RawSnapshot, RawSnapshotLoader, RawSnapshotLoaderFactory, RemoteSnapshotUtil, SnapshotMaker, SnapshotTask, SnapshotTaskSigner}
import ee.cone.c4di.{c4, c4multi, provide}
import okio.ByteString

import scala.annotation.tailrec

@c4multi("RemoteRawSnapshotLoaderImplApp") final class RemoteRawSnapshotLoaderImpl(baseURL: String)(util: HttpUtil) extends RawSnapshotLoader with LazyLogging {
  def load(snapshot: RawSnapshot): ByteString = {
    val tm = NanoTimer()
    val resp = util.get(s"$baseURL/${snapshot.relativePath}", Nil)
    assert(resp.status == 200)
    logger.debug(s"downloaded ${resp.body.size} in ${tm.ms} ms")
    resp.body
  }
}


@c4("MergingSnapshotApp") final class RemoteRawSnapshotLoaderFactory(inner: RemoteRawSnapshotLoaderImplFactory) extends RawSnapshotLoaderFactory {
  def create(baseURL: String): RawSnapshotLoader = inner.create(baseURL)
}

@c4("RemoteRawSnapshotApp") final class RemoteSnapshotUtilImpl(util: HttpUtil) extends RemoteSnapshotUtil with LazyLogging {
  def authHeaders(signed: String): List[(String, String)] =
    List(("x-r-signed", signed))
  def measure[R](f: =>R): R = {
    val startTime = System.currentTimeMillis
    val res = f
    logger.debug(s"Snapshot request time: ${System.currentTimeMillis - startTime}")
    res
  }
  def request(appURL: String, signed: String): ()=>List[RawSnapshot] = measure{
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

@c4("RemoteRawSnapshotApp") final class DefRemoteSnapshotAppURL(config: Config) extends RemoteSnapshotAppURL(config.get("C4HTTP_SERVER"))

@c4("RemoteRawSnapshotApp") final class EnvRemoteRawSnapshotLoader(url: RemoteSnapshotAppURL, factory: RemoteRawSnapshotLoaderImplFactory) {
  @provide def get: Seq[RawSnapshotLoader] = List(factory.create(url.value))
}

@c4("RemoteRawSnapshotApp") final class RemoteSnapshotMaker(
  appURL: RemoteSnapshotAppURL, util: RemoteSnapshotUtil, signer: SnapshotTaskSigner
) extends SnapshotMaker {
  def make(task: SnapshotTask): List[RawSnapshot] =
    util.request(appURL.value, signer.sign(task, System.currentTimeMillis() + 3600*1000))()
}
