package ee.cone.c4actor.dependancy

import ee.cone.c4actor.Types.SrcId

case class Response(request: DepRequest[_], value: Option[_], rqList: List[SrcId] = Nil)
