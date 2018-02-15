package ee.cone.c4actor.dependancy

import ee.cone.c4actor.Types.SrcId

case class ByPKRequest[A](srcId: SrcId, classOf: Class[A], targetSrcId: SrcId, prevSrcId: List[SrcId] = Nil) extends AbstractDepRequest[A] {
  override def extendPrev(id: SrcId): DepRequest[A] = ByPKRequest(srcId, classOf, targetSrcId, id :: prevSrcId)
}

case object ByPKRequestHandler extends RequestHandler[ByPKRequest[_]] {
  override def canHandle = classOf[ByPKRequest[_]]

  override def handle: ByPKRequest[_] => Dep[_] = ???
}
