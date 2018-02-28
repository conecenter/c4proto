package ee.cone.c4actor.dependancy

import ee.cone.c4actor.CtxType.ContextId
import ee.cone.c4actor.dependancy.ContextIdRequestProtocol.ContextIdRequest
import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol object ContextIdRequestProtocol extends Protocol{
  @Id(0x0f31) case class ContextIdRequest()
}

object ContextIdRequestUtility {
  def askContextId = new RequestDep[ContextId](ContextIdRequest())
}
