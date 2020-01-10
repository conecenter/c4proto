package ee.cone.c4actor.dep.request

import ee.cone.c4proto.{Id, protocol}

trait ContextIdRequestProtocolAppBase

@protocol("ContextIdRequestProtocolApp") object ContextIdRequestProtocolBase  {
  @Id(0x0f31) case class N_ContextIdRequest()

  @Id(0x0f3a) case class N_UserIdRequest()

  @Id(0x0f5b) case class N_RoleIdRequest()

  @Id(0x0f5d) case class N_MockRoleRequest()
}

