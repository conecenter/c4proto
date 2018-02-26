package ee.cone.c4actor.dependancy

import ee.cone.c4actor.CtxType.{Ctx, Request}
import ee.cone.c4actor.{AbstractLens, Lens}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4proto.{Protocol, protocol}



case class RequestWithSrcId(srcId: SrcId, request: Request, parentSrcIds: List[SrcId] = Nil) {
  def addParent(id: SrcId): RequestWithSrcId = this.copy(parentSrcIds = id :: this.parentSrcIds)
}
