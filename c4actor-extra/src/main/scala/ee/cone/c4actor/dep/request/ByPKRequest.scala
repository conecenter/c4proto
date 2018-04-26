package ee.cone.c4actor.dep.request

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.request.ByPKRequestProtocol.ByPKRequest
import ee.cone.c4actor.dep.{DepInnerRequest, DepInnerResponse}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by}
import ee.cone.c4proto.{Id, Protocol, protocol}


trait ByPKRequestApi {
  def byPKClasses: List[Class[_ <: Product]] = Nil
}

trait ByPKRequestHandlerApp extends AssemblesApp with ProtocolsApp with ByPKRequestApi {
  override def assembles: List[Assemble] = byPKClasses.distinct.map(className ⇒ new ByPKGenericAssemble(className)) ::: super.assembles

  override def protocols: List[Protocol] = ByPKRequestProtocol :: super.protocols
}

@assemble class ByPKGenericAssemble[A <: Product](handledClass: Class[A]) extends Assemble {
  type ByPkItemSrcId = SrcId

  def BPKRequestWithSrcToItemSrcId(
    key: SrcId,
    requests: Values[DepInnerRequest]
  ): Values[(ByPkItemSrcId, DepInnerRequest)] =
    for (
      rq ← requests
      if rq.request.isInstanceOf[ByPKRequest] && rq.request.asInstanceOf[ByPKRequest].className == handledClass.getName
    ) yield {
      val byPkRq = rq.request.asInstanceOf[ByPKRequest]
      (byPkRq.itemSrcId, rq)
    }

  def BPKRequestToResponse(
    key: SrcId,
    @by[ByPkItemSrcId] requests: Values[DepInnerRequest],
    items: Values[A]
  ): Values[(SrcId, DepInnerResponse)] =
    for (
      rq ← requests
      if rq.request.isInstanceOf[ByPKRequest] && rq.request.asInstanceOf[ByPKRequest].className == handledClass.getName
    ) yield {
      WithPK(DepInnerResponse(rq, Option(items.headOption)))
    }
}

@protocol object ByPKRequestProtocol extends Protocol {

  @Id(0x0fa6) case class ByPKRequest(
    @Id(0x0fa7) className: String,
    @Id(0x0fa8) itemSrcId: String
  )

}