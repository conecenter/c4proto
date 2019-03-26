package ee.cone.c4external.dbrequest

import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.WithPK
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Single, assemble, by, distinct}
import ee.cone.c4external.ExtSatisfactionApi.{ExtSatisfaction, RequestGroupId}
import ee.cone.c4external.ExternalProtocol.CacheResponses
import ee.cone.c4external._
import ee.cone.c4external.dbrequest.ByPKTypes.ByPKRqId
import ee.cone.c4external.joiners.Satisfaction
import ee.cone.dbadapter.{DBAdapter, OrigSchema, OrigSchemaBuilder}

case object ExtByPKType extends ExtDBRequestType {
  val uName: String = "ee.cone.c4external.dbrequest.ByPKRequestType"
}

case class ExtByPKRequest(srcId: SrcId, externalId: ExternalId, modelSrcId: SrcId, modelId: Long) extends ExtDBRequest {
  val rqType: ExtDBRequestType = ExtByPKType
}

class ExtByPKHandler(dbAdapter: DBAdapter, idToOrigSchema: Map[Long, OrigSchema], liveTime: Long) extends ExtDBRequestHandler {
  val externalId: ExternalId = dbAdapter.externalId

  def handle: List[ExtDBRequest] ⇒ List[CacheResponses] = in ⇒ {
    val list: List[ExtByPKRequest] = in.collect { case a: ExtByPKRequest ⇒ a }
    if (list.isEmpty)
      Nil
    else {
      val offset = dbAdapter.getOffset
      val updates = for {
        (id, rqs) ← list.groupBy(_.modelId).toList if idToOrigSchema.contains(id)
        orig ← dbAdapter.getOrigBytes(idToOrigSchema(id), rqs.map(_.srcId))
      } yield {
        orig
      }
      CacheResponses(RandomUUID(), offset, System.currentTimeMillis() + liveTime, list.map(_.srcId), updates) :: Nil
    }
  }

  def supportedType: ExtDBRequestType = ExtByPKType
}

class ExtByPKHandlerFactory(builders: List[OrigSchemaBuilder[_ <: Product]], byPkLiveTime: Long) extends ExtDBRequestHandlerFactory[ExtByPKRequest] {
  val schemaMap: Map[Long, OrigSchema] = builders.map(b ⇒ b.getOrigId → b.getMainSchema).toMap

  def create: DBAdapter ⇒ ExtDBRequestHandler =
    new ExtByPKHandler(_, schemaMap, byPkLiveTime)
}

@assemble class ExtByPKJoinerBase {
  type ByPkCombinedId = SrcId
  type ByPKCacheUpdate = SrcId

  def MockExtByPK(
    fbId: SrcId,
    fb: Each[Firstborn]
  ): Values[(ByPKRqId, ExtByPKRequest)] =
    Nil

  def CombineByPK(
    byPKRq: SrcId,
    @distinct @by[ByPKRqId] byPks: Values[ExtByPKRequest]
  ): Values[(ByPkCombinedId, ExtByPKRequest)] =
    Single.option(byPks).map(rq ⇒ List(WithPK(rq))).getOrElse(Nil)

  def HandleByPKExtRequest(
    rqId: SrcId,
    @by[ByPkCombinedId] rq: Each[ExtByPKRequest],
    @by[ExtSatisfaction] satisfactions: Values[Satisfaction]
  ): Values[(ByPKCacheUpdate, ExtByPKRequest)] =
    if (satisfactions.exists(_.status == 'new))
      Nil
    else
      List(ExtByPKType.uName → rq)

  def HandleMassByPKRequest(
    externalIdWStatus: SrcId,
    @by[ByPKCacheUpdate] rqs: Values[ExtByPKRequest]
  ): Values[(RequestGroupId, ExtDBRequestGroup)] =
    List(WithPK(ExtDBRequestGroup(ExtByPKType.uName, rqs.toList)))
}