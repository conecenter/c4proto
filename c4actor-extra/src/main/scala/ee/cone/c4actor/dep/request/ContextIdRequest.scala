package ee.cone.c4actor.dep.request

import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol object ContextIdRequestProtocol extends Protocol{
  @Id(0x0f31) case class ContextIdRequest()
}

