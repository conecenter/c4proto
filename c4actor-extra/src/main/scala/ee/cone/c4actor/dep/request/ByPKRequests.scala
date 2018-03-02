package ee.cone.c4actor.dep.request

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.{DepRequestWithSrcId, DepResponse}
import ee.cone.c4actor.dep.request.ByPKRequestProtocol.ByPKRequest
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by, was}
import ee.cone.c4proto.{Id, Protocol, protocol}


trait ByPKRequestHandlerApp extends AssemblesApp with ProtocolsApp {
  override def assembles: List[Assemble] = byPKClasses.map(className ⇒ new ByPKGenericAssemble(className)) ::: super.assembles

  def byPKClasses: List[Class[_ <: Product]] = Nil

  override def protocols: List[Protocol] = ByPKRequestProtocol :: super.protocols
}

@assemble class ByPKGenericAssemble[A <: Product](handledClass: Class[A]) extends Assemble {
  type ToResponse = SrcId
  type ByPkItemSrcId = SrcId

  def BPKRequestWithSrcToItemSrcId(
    key: SrcId,
    @was requests: Values[DepRequestWithSrcId]
  ): Values[(ByPkItemSrcId, DepRequestWithSrcId)] =
    for (
      rq ← requests
      if rq.request.isInstanceOf[ByPKRequest]
    ) yield {
      val byPkRq = rq.request.asInstanceOf[ByPKRequest]
      (byPkRq.itemSrcId, rq)
    }

  def BPKRequestToResponse(
    key: SrcId,
    @by[ByPkItemSrcId] requests: Values[DepRequestWithSrcId],
    items: Values[A]
  ): Values[(ToResponse, DepResponse)] =
    (for (
      rq ← requests
      if rq.request.isInstanceOf[ByPKRequest]
    ) yield {
      //println()
      //println(s"ByPK$key:$items")
      val response = DepResponse(rq, Option(items.headOption))
      WithPK(response) :: (for (id ← rq.parentSrcIds) yield (id, response))
    }).flatten
}

@protocol object ByPKRequestProtocol extends Protocol {

  @Id(0x0fa6) case class ByPKRequest(
    @Id(0x0fa7) className: String,
    @Id(0x0fa8) itemSrcId: String
  )

}