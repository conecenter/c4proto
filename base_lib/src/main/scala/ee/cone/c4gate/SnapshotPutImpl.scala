package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{S_FailedUpdates, S_Firstborn}
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{by, c4assemble}
import ee.cone.c4gate.HttpProtocol.S_HttpRequest
import ee.cone.c4di.{c4, c4multi, provide}
import ee.cone.c4gate.SnapshotPutProtocol.S_SnapshotPutDone
import ee.cone.c4proto.{Id, protocol}
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
  def merge(relativePath: String, data: ByteString, addIgnore: Set[Long]): Context=>Context = local => {
    val rawSnapshotLoader = new MemRawSnapshotLoader(relativePath,data)
    val snapshotLoader = snapshotLoaderFactory.create(rawSnapshotLoader)
    val Some(targetFullSnapshot) = snapshotLoader.load(RawSnapshot(relativePath))
    val currentSnapshot = snapshotDiffer.needCurrentSnapshot(local)
    val diffUpdates = snapshotDiffer.diff(currentSnapshot, targetFullSnapshot, addIgnore)
    logger.info(s"put-snapshot activated, ${diffUpdates.size} updates")
    WriteModelKey.modify(_.enqueueAll(diffUpdates))(local)
  }
}

@c4("SnapshotPutApp") final class SnapshotPutIgnores {
  @provide def get: Seq[GeneralSnapshotPatchIgnore] = Seq(new SnapshotPatchIgnore(classOf[S_SnapshotPutDone]))
}

@protocol("SnapshotPutApp") object SnapshotPutProtocol {
  @Id(0x00B8) case class S_SnapshotPutDone(@Id(0x002A) srcId: String)
}

@c4multi("SnapshotPutApp") final case class SnapshotPutTx(srcId: SrcId, requests: List[S_HttpRequest])(
  putter: SnapshotPutter, signatureChecker: SimpleSigner, signedPostUtil: SignedReqUtil, txAdd: LTxAdd,
  getS_FailedUpdates: GetByPK[S_FailedUpdates], getS_SnapshotPutDone: GetByPK[S_SnapshotPutDone],
) extends TxTransform with LazyLogging {
  import signedPostUtil._

  private def prevTxFailed(local: Context): Boolean =
    getS_FailedUpdates.ofA(local).contains(ReadAfterWriteOffsetKey.of(local))

  def transform(local: Context): Context = {
    val reqSuccesses =
      for{ req <- requests; done <- getS_SnapshotPutDone.ofA(local).get(req.srcId).toList } yield (req, done)
    if(reqSuccesses.nonEmpty) Function.chain(Seq(
      respond(reqSuccesses.map{ case (req, _) => req -> Nil }, Nil),
      txAdd.add(reqSuccesses.flatMap{ case (_, done) => LEvent.delete(done) })
    ))(local) else catchNonFatal {
      assert(!prevTxFailed(local))
      val request = requests.head
      assert(request.method == "POST")
      val (relativePath, addIgnore) = signatureChecker.retrieve(check=true)(signed(request.headers)) match {
        case Some(Seq(putter.`url`,relativePath)) => (relativePath, Set.empty[Long])
        case Some(Seq(putter.`url`,relativePath,ignoreStr)) =>
          val ignoreSet =
            (for(found <- """([0-9a-f]+)""".r.findAllIn(ignoreStr)) yield java.lang.Long.parseLong(found, 16)).toSet
          (relativePath, ignoreSet)
      }
      Function.chain(Seq(
        putter.merge(relativePath, request.body, addIgnore),
        txAdd.add(LEvent.update(S_SnapshotPutDone(request.srcId)))
      ))(local)
    }("put-snapshot"){ e =>
      respond(Nil,List(requests.head -> e.getMessage))(local)
    } // failure can happen out of there: >1G request may result in >2G tx, that will be possible to txAdd, but impossible to commit later
  }
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

