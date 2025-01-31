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
    val resp = ReqRetry { () =>
      logger.info(s"downloading snapshot")
      util.get(s"$baseURL/${snapshot.relativePath}", Nil)
    }
    logger.info(s"downloaded ${resp.body.size} in ${tm.ms} ms")
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
      val headers = ReqRetry(()=>util.get(s"$appURL/response/$uuid", Nil)).headers
      headers.getOrElse("x-r-snapshot-keys",Nil) match {
        case Seq(res) => res.split(",").map(RawSnapshot).toList
        case _ => throw new Exception(headers.getOrElse("x-r-error-message",Nil).mkString(";"))
      }
  }
}

object ReqRetry {
  @tailrec final def apply(f: ()=>HttpResponse): HttpResponse = try {
    val res = f()
    if (res.status != 200) throw new FileNotFoundException
    res
  } catch {
    case e: FileNotFoundException =>
      Thread.sleep(1000)
      apply(f)
  }
}
/*
@c4("RemoteRawSnapshotApp") final class EnvRemoteRawSnapshotProvider(
  makerFactory: RemoteSnapshotMakerFactory, config: Config
) {
  @provide def makers: Seq[SnapshotMaker] = Seq(makerFactory.create(config.get("C4HTTP_SERVER")))
}
*/
@c4multi("RemoteRawSnapshotApp") final class RemoteSnapshotMaker(baseURL: String)(
  util: RemoteSnapshotUtil, signer: SnapshotTaskSigner
) extends SnapshotMaker {
  def make(task: SnapshotTask): List[RawSnapshot] =
    util.request(baseURL, signer.sign(task, System.currentTimeMillis() + 3600*1000))()
}
