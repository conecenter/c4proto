package ee.cone.c4actor.dependancy

import ee.cone.c4actor.{AssemblesApp, WithPK}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, was}

//TODO ask ByPK w/ only 1 srcId
case class ByPKRequest[A](srcId: SrcId, classOf: Class[A], prevSrcId: List[SrcId] = Nil) extends AbstractDepRequest[A] {
  override def extendPrev(id: SrcId): DepRequest[A] = ByPKRequest(srcId, classOf, id :: prevSrcId)
}

case object ByPKRequestHandler extends RequestHandler[ByPKRequest[_]] {
  override def canHandle = classOf[ByPKRequest[_]]

  override def handle: ByPKRequest[_] => Dep[_] = request ⇒ new RequestDep(request)
}

trait ByPKRequestHandlerApp extends AssemblesApp with RequestHandlerRegistryApp {
  def handledClasses: List[Class[_]] = Nil

  //override def handlers: List[RequestHandler[_]] = ByPKRequestHandler :: super.handlers

  override def assembles: List[Assemble] = handledClasses.map(className ⇒ new ByPKGenericAssemble(className)) ::: super.assembles
}

@assemble class ByPKGenericAssemble[A](handledClass: Class[A]) extends Assemble {
  type ToResponse = SrcId

  def RequestToResponse(
    key: SrcId,
    @was requests: Values[Request],
    items: Values[A]
  ): Values[(ToResponse, Response)] =
    (for (
      rq ← requests;
      item ← items
    ) yield {
      //println(s"ByPK $key:$requests:$item")
      val response = Response(rq.asInstanceOf[DepRequest[_]], Option(item))
      WithPK(response) :: (for (id ← response.request.prevSrcId) yield (id, response))
    }).flatten
}
