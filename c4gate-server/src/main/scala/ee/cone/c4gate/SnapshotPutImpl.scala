package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{assemble, by}
import ee.cone.c4gate.HttpProtocol.{N_Header, S_HttpPost}
import okio.ByteString

class MemRawSnapshotLoader(relativePath: String, bytes: ByteString) extends RawSnapshotLoader {
  def load(snapshot: RawSnapshot): ByteString = {
    assert(snapshot.relativePath==relativePath)
    bytes
  }
}

class SnapshotPutter(
  signatureChecker: Signer[List[String]],
  snapshotLoaderFactory: SnapshotLoaderFactory,
  snapshotDiffer: SnapshotDiffer
) extends LazyLogging {
  val url = "/put-snapshot"
  def header(headers: List[N_Header], key: String): Option[String] =
    headers.find(_.key == key).map(_.value)
  def signed(headers: List[N_Header]): Option[String] = header(headers,"X-r-signed")
  def merge(post: S_HttpPost): Context⇒Context = local ⇒ {
    val Some(Seq(`url`,relativePath)) =
      signatureChecker.retrieve(check=true)(signed(post.headers))
    val rawSnapshotLoader = new MemRawSnapshotLoader(relativePath,post.body)
    val snapshotLoader = snapshotLoaderFactory.create(rawSnapshotLoader)
    val Some(targetFullSnapshot) = snapshotLoader.load(RawSnapshot(relativePath))
    val currentSnapshot = snapshotDiffer.needCurrentSnapshot(local)
    val diffUpdates = snapshotDiffer.diff(currentSnapshot,targetFullSnapshot)
    logger.info(s"put-snapshot activated, ${diffUpdates.size} updates")
    WriteModelKey.modify(_.enqueue(diffUpdates))(local)
  }
}

case class SnapshotPutTx(srcId: SrcId, post: S_HttpPost)(putter: SnapshotPutter) extends TxTransform {
  def transform(local: Context): Context =
    if(ErrorKey.of(local).nonEmpty) TxAdd(LEvent.delete(post))(local)
    else putter.merge(post)(local)
}

@assemble class SnapshotPutAssembleBase(putter: SnapshotPutter) {
  type PuttingId = SrcId

  def needConsumer(
    key: SrcId,
    first: Each[Firstborn]
  ): Values[(SrcId,LocalPostConsumer)] =
    List(WithPK(LocalPostConsumer(putter.url)))

  def mapAll(
    key: SrcId,
    post: Each[S_HttpPost]
  ): Values[(PuttingId,S_HttpPost)] =
    if(post.path == putter.url) List("snapshotPut"→post) else Nil

  def mapFirstborn(
    key: SrcId,
    @by[PuttingId] posts: Values[S_HttpPost]
  ): Values[(SrcId,TxTransform)] =
    List(WithPK(SnapshotPutTx(key,posts.head)(putter)))
}

// todo: what if 2 posts? how to know if post failed?

