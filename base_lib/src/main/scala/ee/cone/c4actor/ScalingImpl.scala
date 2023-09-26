package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.ScalingProtocol.S_ScaledTxTr
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Single, by, byEq, c4assemble, ignore}
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4proto.{Id, protocol}

@protocol("ScalingApp") object ScalingProtocol {
  @Id(0x00B1) case class S_ScaledTxTr(
    @Id(0x0011) srcId: SrcId,
    @Id(0x00B0) role: String,
    @Id(0x00B2) txTrId: SrcId,
    @Id(0x00B1) electorClientId: SrcId,
  )
}

@c4assemble("ScalingApp") class ScalingAssembleBase(
  actorName: ActorName,
  scaleTxFactory: ScaleTxFactory,
  idGenUtil: IdGenUtil,
  enables: List[GeneralEnableSimpleScaling],
  currentProcess: CurrentProcess,
)(
  clNames: Set[String] = enables.map(_.cl.getName).toSet
){
  type ElectorClientKey = SrcId
  type TxTrKey = SrcId

  @ignore def toDel(scaled: Values[S_ScaledTxTr]): List[LEvent[Product]] =
    scaled.sortBy(_.srcId).toList.flatMap(LEvent.delete(_))

  def gatherScaledByElectorClient(
    key: SrcId,
    scaled: Each[S_ScaledTxTr],
  ): Values[(ElectorClientKey,S_ScaledTxTr)] =
    if(scaled.role==actorName.value) List(scaled.electorClientId->scaled) else Nil

  def enablePurgeScaledTx(
    key: SrcId,
    @by[ElectorClientKey] scaled: Values[S_ScaledTxTr],
    @byEq[SrcId](actorName.value) processes: Each[ReadyProcesses],
  ): Values[(SrcId,TxTransform)] =
    if(processes.all.exists(_.id==key)) Nil
    else List(WithPK(scaleTxFactory.create(s"PurgeScaledTx-$key",toDel(scaled))))

  def gatherScaledByTxTr(
    key: SrcId,
    scaled: Each[S_ScaledTxTr],
  ): Values[(TxTrKey,S_ScaledTxTr)] =
    if(scaled.role==actorName.value) List(scaled.txTrId->scaled) else Nil

  @ignore def toAdd(txTrId: SrcId, electorClientId: SrcId): List[LEvent[Product]] = {
    val id = idGenUtil.srcIdFromStrings(actorName.value,txTrId,electorClientId)
    LEvent.update(S_ScaledTxTr(id,actorName.value,txTrId,electorClientId)).toList
  }

  def enableTxTr(
    key: SrcId,
    txTrs: Values[TxTransform],
    @by[TxTrKey] scaled: Values[S_ScaledTxTr],
    @byEq[SrcId](actorName.value) processes: Each[ReadyProcesses],
  ): Values[(SrcId,EnabledTxTr)] =
    if(processes.enabledForCurrentRole.isEmpty) Nil else {
      val masterId :: followerIds = processes.enabledForCurrentRole
      val worksAtId = Single.option(scaled).fold(masterId)(_.electorClientId)
      if(worksAtId != currentProcess.id) Nil else {
        val scaleToId = if(!txTrs.exists(t=>clNames(t.getClass.getName)) || followerIds.isEmpty) masterId
          else applyMod(followerIds, Math.abs(key.hashCode))
        if(worksAtId == scaleToId) txTrs.map(t=>WithPK(EnabledTxTr(t)))
        else List(WithPK(EnabledTxTr(scaleTxFactory.create(key, toDel(scaled) ::: toAdd(key, scaleToId)))))
      }
    }

  @ignore def applyMod[T](l: Seq[T], i: Int): T = l(i % l.size)
}

@c4multi("ScalingApp") final case class ScaleTx(
  srcId: SrcId, events: List[LEvent[Product]]
)(txAdd: LTxAdd) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    logger.info(s"rescaling: ${events}")
    txAdd.add(events)(local)
  }
}
