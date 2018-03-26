package ee.cone.c4actor.dep

import ee.cone.c4actor.dep.CtxType.DepRequest
import ee.cone.c4actor.Types.SrcId


case class DepRqWithSrcId(srcId: SrcId, request: DepRequest)

case class DepRequestWithSrcId(srcId: SrcId, request: DepRequest, parentSrcIds: List[SrcId] = Nil) {
  def addParent(ids: List[SrcId]): DepRequestWithSrcId = this.copy(parentSrcIds = ids ::: this.parentSrcIds)
}

case class ParentBus(rqId: SrcId, parentSrcIds: SrcId) //TODO possibly multiple parents