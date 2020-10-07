package ee.cone.c4gate_server

import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{AbstractAll, All, byEq, c4assemble}
import ee.cone.c4di.c4multi
import ee.cone.c4gate_server.SnapshotListRequestProtocol.{N_RawSnapshotProto, N_SnapshotInfoProto, S_ListSnapshotsRequest, S_ListSnapshotsResponse}
import ee.cone.c4proto.{Id, protocol}


@protocol("FileRawSnapshotLoaderApp") object SnapshotListRequestProtocol {
  @Id(0x36c9) case class S_ListSnapshotsRequest(
    @Id(0x36ca) source: String
  )

  @Id(0x36cb) case class S_ListSnapshotsResponse(
    @Id(0x36cc) source: String,
    @Id(0x36cd) snapshotsInfo: List[N_SnapshotInfoProto]
  )

  @Id(0x36ce) case class N_SnapshotInfoProto(
    @Id(0x36cf) subDirStr: String,
    @Id(0x36d0) offset: String,
    @Id(0x36d1) uuid: String,
    @Id(0x36d2) raw: Option[N_RawSnapshotProto],
    @Id(0x36d5) creationDate: Long
  )

  @Id(0x36d3) case class N_RawSnapshotProto(
    @Id(0x36d4) relativePath: String
  )
}

@c4assemble("FileRawSnapshotLoaderApp") class SnapshotListRequestAssembleBase(
  requestTransformFactory: SnapshotListRequestTransformFactory
) {
  type SnapshotRequestAll = AbstractAll
  def processRequest(
    srcId: SrcId,
    firstborn: Each[S_Firstborn],
    @byEq[SnapshotRequestAll](All) requests: Values[S_ListSnapshotsRequest]
  ): Values[(SrcId, TxTransform)] =
    WithPK(requestTransformFactory.create(requests.toList)) :: Nil
}

@c4multi("FileRawSnapshotLoaderApp") final case class SnapshotListRequestTransform(requests: List[S_ListSnapshotsRequest])(
  snapshotLister: SnapshotLister,
  txAdd: LTxAdd
) extends TxTransform {
  import ProtoConversions._
  def transform(local: Context): Context = {
    if (requests.nonEmpty) {
      if (ErrorKey.of(local).isEmpty) {
        val response: S_ListSnapshotsResponse = snapshotLister.list
        //PrintColored("g")(s"ListSnapshotsRequestHandler success with ${response.snapshotsInfo.size}")
        txAdd.add(requests.flatMap(LEvent.delete) ++ LEvent.update(response))(local)
      } else {
        //PrintColored("r")("ListSnapshotsRequestHandler fail")
        (txAdd.add(requests.flatMap(LEvent.delete)) andThen ErrorKey.set(Nil)) (local)
      }
    } else
      local
  }
}

object ProtoConversions {
  implicit def RawSnapshotDeProto(raw: N_RawSnapshotProto): RawSnapshot =
    RawSnapshot(raw.relativePath)

  implicit def RawSnapshotToProto(raw: RawSnapshot): N_RawSnapshotProto =
    N_RawSnapshotProto(raw.relativePath)

  implicit def RawSnapshotDeProtoList(raw: List[N_RawSnapshotProto]): List[RawSnapshot] =
    raw.map(r => r: RawSnapshot)

  implicit def RawSnapshotToProtoList(raw: List[RawSnapshot]): List[N_RawSnapshotProto] =
    raw.map(r => r: N_RawSnapshotProto)

  implicit def SnapshotInfoToProto(info: SnapshotInfo): N_SnapshotInfoProto =
    N_SnapshotInfoProto(info.subDirStr, info.offset, info.uuid, Option(info.raw), info.creationTime)

  implicit def ListSnapshotInfoToResponse(list: List[SnapshotInfo]): S_ListSnapshotsResponse = {
    S_ListSnapshotsResponse("response", list.map { info => info: N_SnapshotInfoProto })
  }
}