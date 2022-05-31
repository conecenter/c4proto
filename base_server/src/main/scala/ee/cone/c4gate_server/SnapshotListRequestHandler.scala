package ee.cone.c4gate_server

import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.SnapshotListRequestProtocol.{N_RawSnapshotInfoProto, N_SnapshotInfoProto, S_ListSnapshotsRequest, S_ListSnapshotsResponse}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{AbstractAll, All, byEq, c4assemble}
import ee.cone.c4di.c4multi



@c4assemble("SnapshotListRequestHandlerApp") class SnapshotListRequestAssembleBase(
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

@c4multi("SnapshotListRequestHandlerApp") final case class SnapshotListRequestTransform(requests: List[S_ListSnapshotsRequest])(
  snapshotLister: SnapshotLister,
  txAdd: LTxAdd
) extends TxTransform {
  def transform(local: Context): Context = {
    if (requests.nonEmpty) {
      if (ErrorKey.of(local).isEmpty) {
        val response: S_ListSnapshotsResponse = toResponse(snapshotLister.listWithMTime)
        //PrintColored("g")(s"ListSnapshotsRequestHandler success with ${response.snapshotsInfo.size}")
        txAdd.add(requests.flatMap(LEvent.delete) ++ LEvent.update(response))(local)
      } else {
        //PrintColored("r")("ListSnapshotsRequestHandler fail")
        (txAdd.add(requests.flatMap(LEvent.delete)) andThen ErrorKey.set(Nil)) (local)
      }
    } else
      local
  }
  def toProto(timed: TimedSnapshotInfo): N_SnapshotInfoProto = {
    val info = timed.snapshot
    val raw = N_RawSnapshotInfoProto(info.raw.relativePath)
    N_SnapshotInfoProto(info.subDirStr, info.offset, info.uuid, Option(raw), timed.mTime)
  }
  def toResponse(list: List[TimedSnapshotInfo]): S_ListSnapshotsResponse = {
    S_ListSnapshotsResponse("response", list.map(toProto))
  }
}