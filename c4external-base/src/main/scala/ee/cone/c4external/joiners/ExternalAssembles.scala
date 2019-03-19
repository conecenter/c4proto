package ee.cone.c4external.joiners

import ee.cone.c4actor.QProtocolBase.Firstborn
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{All, assemble, by, ns}
import ee.cone.c4external.ExtSatisfactionApi.{ExtSatisfaction, RequestGroupId}
import ee.cone.c4external.ExternalProtocol._
import ee.cone.c4external.joiners.ExternalAssembleTypes.ExtAll
import ee.cone.c4external.{ExtDBRequestGroup, ExternalId}

object ExternalAssembleTypes {
  type ExtAll = All
}

case class ExternalIdBind(externalName: String, externalId: ExternalId)

@assemble class ExternalAllAssembleBase(extUName: String, externalId: ExternalId) {
  def ProduceExternalBind(
    fbId: SrcId,
    fb: Each[Firstborn]
  ): Values[(SrcId, ExternalIdBind)] =
    List(WithPK(ExternalIdBind(externalId.uName, externalId)))

  def ExtOffsetToAll(
    externalName: SrcId,
    bind: Each[ExternalIdBind],
    dbOffset: Each[ExternalOffset]
  ): Values[(ExtAll@ns(extUName), ExternalOffset)] =
    WithAll(dbOffset) :: Nil

  def ExtTimeToAll(
    externalName: SrcId,
    bind: Each[ExternalIdBind],
    extTime: Each[ExternalTime]
  ): Values[(ExtAll@ns(extUName), ExternalTime)] =
    WithAll(extTime) :: Nil

  def ExtMinOffsetToAll(
    externalName: SrcId,
    bind: Each[ExternalIdBind],
    extMinOffset: Each[ExternalMinValidOffset]
  ): Values[(ExtAll@ns(extUName), ExternalMinValidOffset)] =
    List(WithAll(extMinOffset))


}

case class ExtUpdatesToSync(srcId: String, externals: List[ExternalUpdates])

@assemble class ExtUpdatesToSyncAssembleBase(extUName: String, externalId: ExternalId) {
  type ExtToSync = SrcId

  def ExtUpdatesToSyncGather(
    extUpdId: SrcId,
    extUpd: Each[ExternalUpdates],
    @by[ExtAll@ns(extUName)] extOffset: Each[ExternalOffset]
  ): Values[(ExtToSync@ns(extUName), ExternalUpdates)] =
    if (extUpd.txId > extOffset.offset)
      List(externalId.uName → extUpd)
    else
      Nil

  def ExtUpdatesToSyncPacking(
    externalName: SrcId,
    bind: Each[ExternalIdBind],
    @by[ExtToSync@ns(extUName)] toSyncExts: Values[ExternalUpdates]
  ): Values[(SrcId, ExtUpdatesToSync)] =
    if (toSyncExts.nonEmpty)
      List(WithPK(ExtUpdatesToSync(externalName, toSyncExts.toList)))
    else
      Nil
}

case class ToDeleteOrig(srcId: SrcId, typeId: Long)

case class ExternalsToDelete(externalId: SrcId, extUpdates: List[ToDeleteOrig], cacheResponses: List[ToDeleteOrig])

@assemble class DeleteContainerCreationBase(
  extUName: String, externalId: ExternalId,
  qAdapterRegistry: QAdapterRegistry,
)(
  extUpdatesId: Long = qAdapterRegistry.byName(classOf[ExternalUpdates].getName).id,
  cacheResponseId: Long = qAdapterRegistry.byName(classOf[CacheResponses].getName).id
){
  type ToDelete = SrcId

  def ExtUpdatesForDeletionCreate(
    extUpdId: SrcId,
    extUpd: Each[ExternalUpdates],
    @by[ExtAll@ns(extUName)] minOffset: Each[ExternalMinValidOffset]
  ): Values[(ToDelete@ns(extUName), ToDeleteOrig)] =
    if (extUpd.txId < minOffset.minValidOffset)
      List(minOffset.externalName → ToDeleteOrig(extUpd.srcId, extUpdatesId))
    else
      Nil

  def CacheResponsesForDeletionCreate(
    cacheRespId: SrcId,
    cacheResp: Each[CacheResponses],
    @by[ExtAll@ns(extUName)] minOffset: Each[ExternalMinValidOffset]
  ): Values[(ToDelete@ns(extUName), ToDeleteOrig)] =
    if (cacheResp.extOffset < minOffset.minValidOffset)
      List(minOffset.externalName → ToDeleteOrig(cacheResp.srcId, cacheResponseId))
    else
      Nil

  def GarbageContainerCreation(
    externalId: SrcId,
    bind: Each[ExternalIdBind],
    @by[ToDelete@ns(extUName)] eufd: Values[ToDeleteOrig],
    @by[ToDelete@ns(extUName)] crfd: Values[ToDeleteOrig]
  ): Values[(SrcId, ExternalsToDelete)] =
    if (eufd.nonEmpty || crfd.nonEmpty)
      List(WithPK(ExternalsToDelete(externalId, eufd.toList, crfd.toList)))
    else
      Nil
}

case class Satisfaction(srcId: SrcId, respId: SrcId, externalId: SrcId, offset: NextOffset, status: Symbol) // 'new, 'old

case class MinOffsetResponse(externalId: SrcId, minOffsetOpt: Option[NextOffset])

case class RequestsToProcess(externalId: SrcId, rqs: List[ExtDBRequestGroup])

@assemble class RequestResolvingBase(extUName: String, externalId: ExternalId) {
  /**
    * Creates satisfaction for every request id inside
    *
    * @return (rqKey, Satisfaction)
    */
  def ProduceSatisfaction(
    responseId: SrcId,
    response: Each[CacheResponses],
    @by[ExtAll@ns(extUName)] offset: Each[ExternalOffset],
    @by[ExtAll@ns(extUName)] time: Each[ExternalTime]
  ): Values[(ExtSatisfaction, Satisfaction)] = {
    val status: Symbol = if (response.extOffset == offset.offset || response.validUntil > time.time) 'new else 'old
    val offsetR: String = response.extOffset
    val srcId: String = status + response.srcId // Trick to optimize satisfactions.exists(_.status == 1) in the next joiner
    for {
      reqId ← time.externalName :: response.reqIds
    } yield reqId → Satisfaction(srcId, response.srcId, time.externalName, offsetR, status)
  }

  def MinOffsetCalculation(
    externalId: SrcId,
    bind: Each[ExternalIdBind],
    @by[ExtSatisfaction] cacheStatus: Values[Satisfaction]
  ): Values[(SrcId, MinOffsetResponse)] =
    if (cacheStatus.nonEmpty) {
      val minOffset: Option[NextOffset] = MinOpt(cacheStatus.filter(_.status == 'new).map(_.offset))
      List(WithPK(MinOffsetResponse(externalId, minOffset)))
    } else
      Nil

  def RequestsCollector(
    externalName: String,
    bind: Each[ExternalIdBind],
    @by[RequestGroupId] groups: Values[ExtDBRequestGroup]
  ): Values[(SrcId, RequestsToProcess)] =
    if (groups.nonEmpty)
      List(WithPK(RequestsToProcess(bind.externalName, groups.toList)))
    else
      Nil
}
