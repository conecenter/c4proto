package ee.cone.c4ui

import ee.cone.c4proto.{Id, protocol}

@protocol("TestCoWorkApp") object TestFilterProtocolBase {
  @Id(0x0005) case class B_Content(
    @Id(0x0006) sessionKey: String,
    @Id(0x0007) value: String
  )
}

