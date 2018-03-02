package ee.cone.c4actor.dep

import ee.cone.c4actor.dep.CtxType.Request
import ee.cone.c4actor.Types.SrcId


case class RequestWithSrcId(srcId: SrcId, request: Request, parentSrcIds: List[SrcId] = Nil) {
  def addParent(id: SrcId): RequestWithSrcId = this.copy(parentSrcIds = id :: this.parentSrcIds)
}

//
