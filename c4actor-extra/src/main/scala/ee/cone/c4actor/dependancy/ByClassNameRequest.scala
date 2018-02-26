package ee.cone.c4actor.dependancy

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{AssemblesApp, ProtocolsApp, WithPK}
import ee.cone.c4actor.dependancy.ByClassNameRequestProtocol.ByClassNameRequest
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by, was}
import ee.cone.c4proto.{Id, Protocol, protocol}
import DepAssembleUtils._
import ByClassNameRequestUtils._

case object ByClassNameRequestHandler extends RequestHandler[ByClassNameRequest] {
  def canHandle: Class[ByClassNameRequest] = classOf[ByClassNameRequest]

  def handle: ByClassNameRequest => Dep[_] = request ⇒ new RequestDep(request)
}

trait ByClassNameRequestHandlerApp extends AssemblesApp with RequestHandlerRegistryApp with ProtocolsApp {
  def byClassNameClasses: List[Class[_ <: Product]] = Nil

  override def protocols: List[Protocol] = ByClassNameRequestProtocol :: super.protocols

  override def assembles: List[Assemble] = byClassNameClasses.map(className ⇒ new ByClassNameGenericAssemble(className, stringToKey(className.getName))) ::: super.assembles
}

@assemble class ByClassNameGenericAssemble[A <: Product](handledClass: Class[A], classSrcId: SrcId) extends Assemble {
  type ToResponse = SrcId
  type ByCNSrcId = SrcId
  type ByCNRqSrcId = SrcId

  def ItemsOnSrcId(
    key: SrcId,
    items: Values[A]
  ): Values[(ByCNSrcId, A)] =
    for (
      item ← items
    ) yield {
      (classSrcId, item)
    }

  def RequestToClassSrcId(
    key: SrcId,
    @was requests: Values[RequestWithSrcId]
  ): Values[(ByCNRqSrcId, RequestWithSrcId)] =
    for (
      rq ← requests
      if rq.request.isInstanceOf[ByClassNameRequest] && rq.request.asInstanceOf[ByClassNameRequest].className == handledClass.getName
    ) yield {
      val byCNRq = rq.request.asInstanceOf[ByClassNameRequest]
      (stringToKey(byCNRq.className), rq)
    }

  def RequestToResponse(
    key: SrcId,
    @by[ByCNRqSrcId] requests: Values[RequestWithSrcId],
    @by[ByCNSrcId] items: Values[A]
  ): Values[(ToResponse, Response)] =
    (for (
      rq ← requests
      if rq.request.isInstanceOf[ByClassNameRequest]
    ) yield {
      val byCNRq = rq.request.asInstanceOf[ByClassNameRequest]
      val response = Response(rq, Option(takeWithDefaultParams(items.toList)(byCNRq.from)(byCNRq.count)))
      WithPK(response) :: (for (id ← rq.parentSrcIds) yield (id, response))
    }
      ).flatten

}

@protocol object ByClassNameRequestProtocol extends Protocol {

  @Id(0x0f26) case class ByClassNameRequest(
    @Id(0x0f27) className: String,
    @Id(0x0f28) from: Int,
    @Id(0x0f29) count: Int
  )

}

object ByClassNameRequestUtils {

  private def customTake[A]:List[A] ⇒ Int => List[A] = list ⇒ count ⇒ if (count < 0) list else list.take(count)
  def takeWithDefaultParams[A]:List[A] ⇒ Int ⇒ Int ⇒ List[A] = list ⇒ from ⇒ count ⇒ customTake(list.drop(from))(count)
}