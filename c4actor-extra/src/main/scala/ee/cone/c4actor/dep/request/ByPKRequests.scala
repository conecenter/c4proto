package ee.cone.c4actor.dep.request

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.{RequestWithSrcId, Response}
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

  def RequestWithSrcToItemSrcId(
    key: SrcId,
    @was requests: Values[RequestWithSrcId]
  ): Values[(ByPkItemSrcId, RequestWithSrcId)] =
    for (
      rq ← requests
      if rq.request.isInstanceOf[ByPKRequest]
    ) yield {
      val byPkRq = rq.request.asInstanceOf[ByPKRequest]
      (byPkRq.itemSrcId, rq)
    }

  def RequestToResponse(
    key: SrcId,
    @by[ByPkItemSrcId] requests: Values[RequestWithSrcId],
    items: Values[A]
  ): Values[(ToResponse, Response)] =
    (for (
      rq ← requests
      if rq.request.isInstanceOf[ByPKRequest]
    ) yield {
      //println()
      //println(s"ByPK$key:$items")
      val response = Response(rq, Option(items.headOption))
      WithPK(response) :: (for (id ← rq.parentSrcIds) yield (id, response))
    }).flatten
}

@protocol object ByPKRequestProtocol extends Protocol {

  @Id(0x0fa6) case class ByPKRequest(
    @Id(0x0fa7) className: String,
    @Id(0x0fa8) itemSrcId: String
  )

}