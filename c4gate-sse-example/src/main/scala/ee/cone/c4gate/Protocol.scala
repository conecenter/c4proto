package ee.cone.c4gate

import ee.cone.c4proto._

@protocol object TestFilterProtocol extends Protocol {
  @Id(0x0005) case class Content(
    @Id(0x0006) sessionKey: String,
    @Id(0x0007) value: String
  )
}

