package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{assemble, by}
import ee.cone.c4gate.HttpProtocol.{N_Header, S_HttpPost}
import okio.ByteString

import scala.collection.immutable.Seq

class MemRawSnapshotLoader(relativePath: String, bytes: ByteString) extends RawSnapshotLoader {
  def load(snapshot: RawSnapshot): ByteString = {
    assert(snapshot.relativePath==relativePath)
    bytes
  }
}

class SnapshotPutter(
  snapshotLoaderFactory: SnapshotLoaderFactory,
  snapshotDiffer: SnapshotDiffer
) extends LazyLogging {
  val url = "/put-snapshot"
  def merge(relativePath: String, data: ByteString): Context⇒Context = local ⇒ {
    val rawSnapshotLoader = new MemRawSnapshotLoader(relativePath,data)
    val snapshotLoader = snapshotLoaderFactory.create(rawSnapshotLoader)
    val Some(targetFullSnapshot) = snapshotLoader.load(RawSnapshot(relativePath))
    val currentSnapshot = snapshotDiffer.needCurrentSnapshot(local)
    val diffUpdates = snapshotDiffer.diff(currentSnapshot,targetFullSnapshot)
    logger.info(s"put-snapshot activated, ${diffUpdates.size} updates")
    WriteModelKey.modify(_.enqueue(diffUpdates))(local)
  }
}

case class SnapshotPutTx(srcId: SrcId, posts: List[S_HttpPost])(
  putter: SnapshotPutter, signatureChecker: Signer[List[String]], signedPostUtil: SignedPostUtil
) extends TxTransform {
  import signedPostUtil._
  def transform(local: Context): Context = catchNonFatal {
    val post = posts.head
    val Some(Seq(putter.`url`,relativePath)) =
      signatureChecker.retrieve(check=true)(signed(post.headers))
    Function.chain(Seq(
      putter.merge(relativePath, post.body),
      respond(List(post→Nil),posts.tail.map(_→"Ignored"))
    ))(local)
  }{ e ⇒
    respond(Nil,List(posts.head → e.getMessage))(local)
  }
}

@assemble class SnapshotPutAssembleBase(putter: SnapshotPutter, signatureChecker: Signer[List[String]], signedPostUtil: SignedPostUtil) {
  type PuttingId = SrcId

  def needConsumer(
    key: SrcId,
    first: Each[S_Firstborn]
  ): Values[(SrcId,LocalPostConsumer)] =
    List(WithPK(LocalPostConsumer(putter.url)))

  def mapAll(
    key: SrcId,
    post: Each[S_HttpPost]
  ): Values[(PuttingId,S_HttpPost)] =
    if(post.path == putter.url) List("snapshotPut"→post) else Nil

  def mapTx(
    key: SrcId,
    @by[PuttingId] posts: Values[S_HttpPost]
  ): Values[(SrcId,TxTransform)] =
    List(WithPK(SnapshotPutTx(key,posts.toList.sortBy(_.srcId))(putter,signatureChecker,signedPostUtil)))
}

// how to know if post failed?

