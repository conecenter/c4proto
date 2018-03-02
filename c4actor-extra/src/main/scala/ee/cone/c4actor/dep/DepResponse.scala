package ee.cone.c4actor.dep

import ee.cone.c4actor.Types.SrcId

case class DepResponse(request: DepRequestWithSrcId, value: Option[_], rqList: List[SrcId] = Nil)
