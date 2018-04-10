package ee.cone.c4actor.dep.request

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep._
import ee.cone.c4actor.dep.request.ByClassNameRequestProtocol.ByClassNameRequest
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by, was}
import ee.cone.c4proto.{Id, Protocol, protocol}

trait ByClassNameRequestHandlerApp extends AssemblesApp with ProtocolsApp with DepAssembleUtilityImpl {
  def byClassNameClasses: List[Class[_ <: Product]] = Nil

  override def protocols: List[Protocol] = ByClassNameRequestProtocol :: super.protocols

  override def assembles: List[Assemble] = byClassNameClasses.map(className ⇒ new ByClassNameGenericAssemble(className, stringToKey(className.getName))) ::: super.assembles
}

@assemble class ByClassNameGenericAssemble[A <: Product](handledClass: Class[A], classSrcId: SrcId) extends Assemble with ByClassNameRequestUtils {
  type ByCNSrcId = SrcId
  type ByCNRqSrcId = SrcId

  def BCNItemsOnSrcId(
    key: SrcId,
    items: Values[A]
  ): Values[(ByCNSrcId, A)] =
    for (
      item ← items
    ) yield {
      (classSrcId+"ByCN", item)
    }

  def BCNRequestToClassSrcId(
    key: SrcId,
    requests: Values[DepInnerRequest]
  ): Values[(ByCNRqSrcId, DepInnerRequest)] =
    for (
      rq ← requests
      if rq.request.isInstanceOf[ByClassNameRequest] && rq.request.asInstanceOf[ByClassNameRequest].className == handledClass.getName
    ) yield {
      (classSrcId+"ByCN", rq)
    }

  def BCNRequestToResponse(
    key: SrcId,
    @by[ByCNRqSrcId] requests: Values[DepInnerRequest],
    @by[ByCNSrcId] items: Values[A]
  ): Values[(SrcId, DepInnerResponse)] =
    for (
      rq ← requests
      if rq.request.isInstanceOf[ByClassNameRequest] && rq.request.asInstanceOf[ByClassNameRequest].className == handledClass.getName
    ) yield {
      val byCNRq = rq.request.asInstanceOf[ByClassNameRequest]
      WithPK(DepInnerResponse(rq, Option(takeWithDefaultParams(items.toList)(byCNRq.from)(byCNRq.count))))
    }

}

@protocol object ByClassNameRequestProtocol extends Protocol {

  @Id(0x0f26) case class ByClassNameRequest(
    @Id(0x0f27) className: String,
    @Id(0x0f28) from: Int,
    @Id(0x0f29) count: Int
  )

}

trait ByClassNameRequestUtils {
  private def customTake[A]: List[A] ⇒ Int => List[A] = list ⇒ count ⇒ if (count < 0) list else list.take(count)

  def takeWithDefaultParams[A]: List[A] ⇒ Int ⇒ Int ⇒ List[A] = list ⇒ from ⇒ count ⇒ customTake(list.drop(from))(count)
}