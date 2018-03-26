package ee.cone.c4actor.dep

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.CtxType.DepCtx

case class DepResponse(request: DepRequestWithSrcId, value: Option[_], rqList: List[SrcId] = Nil)

case class DepCtxResponses(srcId: SrcId, ctx: List[DepResponse])
