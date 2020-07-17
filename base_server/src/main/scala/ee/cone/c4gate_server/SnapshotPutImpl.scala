package ee.cone.c4gate_server

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{N_Update, S_Firstborn}
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{assemble, by, c4assemble}
import ee.cone.c4gate.HttpProtocol.{N_Header, S_HttpRequest, S_HttpResponse}
import ee.cone.c4di.c4
import ee.cone.c4gate._
import ee.cone.c4proto.ToByteString
import okio.ByteString

import scala.collection.immutable.Seq

class MemRawSnapshotLoader(relativePath: String, bytes: ByteString) extends RawSnapshotLoader {
  def load(snapshot: RawSnapshot): ByteString = {
    assert(snapshot.relativePath==relativePath)
    bytes
  }
}

@c4("SnapshotPutApp") final class UpdatesFromPost(
  signatureChecker: Signer[List[String]],
  snapshotLoaderFactory: SnapshotLoaderFactory,
  signedPostUtil: SignedReqUtil,
  toUpdate: ToUpdate,
) {
  def get(request: S_HttpRequest): List[N_Update] = {
    val url = request.path
    val data = request.body
    val Some(Seq(`url`,relativePath)) =
      signatureChecker.retrieve(check=true)(signedPostUtil.signed(request.headers))
    val rawSnapshotLoader = new MemRawSnapshotLoader(relativePath,data)
    val snapshotLoader = snapshotLoaderFactory.create(rawSnapshotLoader)
    val Some(rawEvent) = snapshotLoader.load(RawSnapshot(relativePath))
    toUpdate.toUpdates(List(rawEvent))
  }
}

@c4("SnapshotPutApp") final class SnapshotPutHandler(
  updatesFromPost: UpdatesFromPost,
  signedPostUtil: SignedReqUtil,
  snapshotDiffer: SnapshotDiffer,
  txAdd: LTxAdd,
) extends SeqPostHandler with LazyLogging {
  def url: String = "/put-snapshot"
  def handle(request: S_HttpRequest): Context => Context = local => {
    val targetFullUpdates = updatesFromPost.get(request)
    val currentFullUpdates = snapshotDiffer.needCurrentUpdates(local)
    val diffUpdates = snapshotDiffer.diff(currentFullUpdates,targetFullUpdates)
    logger.info(s"put-snapshot activated, ${diffUpdates.size} updates")
    Function.chain(Seq(
      WriteModelKey.modify(_.enqueueAll(diffUpdates)),
      txAdd.add(signedPostUtil.respond(List(request->Nil),Nil))
    ))(local)
  }
  def handleError(request: S_HttpRequest, error: Throwable): Context => Context = {
    txAdd.add(signedPostUtil.respond(Nil,List(request -> error.getMessage)))
  }
}
// requests.tail.map(_->"Ignored") -- removed; anyway on put-snapshot we lie
// to all other request makers

// how to know if post failed?

@c4("SnapshotPutApp") final class RawUpdateHandler(
  updatesFromPost: UpdatesFromPost,
  txAdd: LTxAdd,
) extends SeqPostHandler {
  def url: String = "/raw-update"
  def handle(request: S_HttpRequest): Context => Context = {
    val updates = updatesFromPost.get(request)
    val resp = S_HttpResponse(request.srcId,201,Nil,ByteString.EMPTY,System.currentTimeMillis)
    val events = LEvent.delete(request) ++ LEvent.update(resp)
    Function.chain(Seq(
      WriteModelKey.modify(_.enqueueAll(updates)),
      txAdd.add(events)
    ))
  }
  def handleError(request: S_HttpRequest, error: Throwable): Context => Context = {
    val resp = S_HttpResponse(request.srcId,500,Nil,ToByteString(),System.currentTimeMillis)
    val events = LEvent.delete(request) ++ LEvent.update(resp)
    txAdd.add(events)
  }

}