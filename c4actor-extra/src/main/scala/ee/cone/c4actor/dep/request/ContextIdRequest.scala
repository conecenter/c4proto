package ee.cone.c4actor.dep.request

import ee.cone.c4actor.dep.DepRequestCat
import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol(DepRequestCat) object ContextIdRequestProtocolBase  {
  @Id(0x0f31) case class ContextIdRequest()

  @Id(0x0f3a) case class UserIdRequest()

  @Id(0x0f5b) case class RoleIdRequest()

  @Id(0x0f5d) case class MockRoleRequest()
}

