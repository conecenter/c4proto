package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{by, c4assemble}
import ee.cone.c4gate.HttpProtocol.S_HttpRequest
import ee.cone.c4di.{c4, c4multi}
import okio.ByteString

class MemRawSnapshotLoader(relativePath: String, bytes: ByteString) extends RawSnapshotLoader {
  def load(snapshot: RawSnapshot): ByteString = {
    assert(snapshot.relativePath==relativePath)
    bytes
  }
}

@c4("SnapshotPutApp") final class SnapshotPutter(
  snapshotLoaderFactory: SnapshotLoaderFactory,
  snapshotDiffer: SnapshotDiffer,
) extends LazyLogging {
  val url = "/put-snapshot"
  def merge(relativePath: String, data: ByteString): Context=>Context = local => {
    val rawSnapshotLoader = new MemRawSnapshotLoader(relativePath,data)
    val snapshotLoader = snapshotLoaderFactory.create(rawSnapshotLoader)
    val Some(targetFullSnapshot) = snapshotLoader.load(RawSnapshot(relativePath))
    val currentSnapshot = snapshotDiffer.needCurrentSnapshot(local)
    val diffUpdates = snapshotDiffer.diff(currentSnapshot, targetFullSnapshot, Set.empty)
    logger.info(s"put-snapshot activated, ${diffUpdates.size} updates")
    WriteModelKey.modify(_.enqueueAll(diffUpdates))(local)
  }
}

@c4multi("SnapshotPutApp") final case class SnapshotPutTx(srcId: SrcId, requests: List[S_HttpRequest])(
  putter: SnapshotPutter, signatureChecker: SimpleSigner, signedPostUtil: SignedReqUtil
) extends TxTransform {
  import signedPostUtil._
  def transform(local: Context): Context = catchNonFatal {
    val request = requests.head
    assert(request.method == "POST")
    val Some(Seq(putter.`url`,relativePath)) =
      signatureChecker.retrieve(check=true)(signed(request.headers))
    Function.chain(Seq(
      putter.merge(relativePath, request.body),
      respond(List(request->Nil),requests.tail.map(_->"Ignored"))
    ))(local)
  }("put-snapshot"){ e =>
    respond(Nil,List(requests.head -> e.getMessage))(local)
  } // failure can happen out of there: >1G request may result in >2G tx, that will be possible to txAdd, but impossible to commit later
}

@c4assemble("SnapshotPutApp") class SnapshotPutAssembleBase(
  snapshotPutTxFactory: SnapshotPutTxFactory, putter: SnapshotPutter
) {
  type PuttingId = SrcId

  def needConsumer(
    key: SrcId,
    first: Each[S_Firstborn]
  ): Values[(SrcId,LocalHttpConsumer)] =
    List(WithPK(LocalHttpConsumer(putter.url)))

  def mapAll(
    key: SrcId,
    req: Each[S_HttpRequest]
  ): Values[(PuttingId,S_HttpRequest)] =
    if(req.path == putter.url) List("snapshotPut"->req) else Nil

  def mapTx(
    key: SrcId,
    @by[PuttingId] requests: Values[S_HttpRequest]
  ): Values[(SrcId,TxTransform)] =
    List(WithPK(snapshotPutTxFactory.create(key,requests.toList.sortBy(_.srcId))))
}

// how to know if post failed?
