package ee.cone.c4actor.dep

import ee.cone.c4actor.dep.CtxType.DepRequest
import ee.cone.c4actor.Types.SrcId

case class DepInnerRequest(srcId: SrcId, request: DepRequest) //TODO Store serialized version
case class DepOuterRequest(srcId: SrcId, innerRequest: DepInnerRequest, parentSrcId: SrcId)
