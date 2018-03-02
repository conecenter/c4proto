package ee.cone.c4actor.dep

import ee.cone.c4actor.dep.CtxType.DepRequest
import ee.cone.c4actor.Types.SrcId


case class DepRequestWithSrcId(srcId: SrcId, request: DepRequest, parentSrcIds: List[SrcId] = Nil) {
  def addParent(id: SrcId): DepRequestWithSrcId = this.copy(parentSrcIds = id :: this.parentSrcIds)
}

//
