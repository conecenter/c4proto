package ee.cone.c4gate_server

import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.SnapshotListRequestProtocol.{N_SnapshotInfoProto, S_ListSnapshotsRequest, S_ListSnapshotsResponse}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{AbstractAll, All, byEq, c4assemble}
import ee.cone.c4di.c4multi



@c4assemble("FileRawSnapshotLoaderApp") class SnapshotListRequestAssembleBase(
  requestTransformFactory: SnapshotListRequestTransformFactory
) {
  type SnapshotRequestAll = AbstractAll

  def allRequests(
    srcId: SrcId,
    request: Each[S_ListSnapshotsRequest]
  ): Values[(SnapshotRequestAll, S_ListSnapshotsRequest)]
  = All -> request :: Nil

  def processRequest(
    srcId: SrcId,
    firstborn: Each[S_Firstborn],
    @byEq[SnapshotRequestAll](All) requests: Values[S_ListSnapshotsRequest]
  ): Values[(SrcId, TxTransform)] = {
    if (requests.nonEmpty)
      WithPK(requestTransformFactory.create(requests.toList)) :: Nil
    else Nil
  }
}

@c4multi("FileRawSnapshotLoaderApp") final case class SnapshotListRequestTransform(requests: List[S_ListSnapshotsRequest])(
  snapshotLister: SnapshotLister,
  val snapshotMTime: SnapshotMTime,
  txAdd: LTxAdd
) extends TxTransform {
  def transform(local: Context): Context = {
    if (requests.nonEmpty) {
      if (ErrorKey.of(local).isEmpty) {
        val response: S_ListSnapshotsResponse = createResponse(snapshotLister.list)
        //PrintColored("g")(s"ListSnapshotsRequestHandler success with ${response.snapshotsInfo.size}")
        txAdd.add(requests.flatMap(LEvent.delete) ++ LEvent.update(response))(local)
      } else {
        //PrintColored("r")("ListSnapshotsRequestHandler fail")
        (txAdd.add(requests.flatMap(LEvent.delete)) andThen ErrorKey.set(Nil)) (local)
      }
    } else
      local
  }

  def createResponse(info: List[SnapshotInfo]): S_ListSnapshotsResponse =
    S_ListSnapshotsResponse("response",
      info.map(
        i => N_SnapshotInfoProto(
          i.raw.relativePath,
          snapshotMTime.mTime(i.raw)
        )
      )
    )
}
