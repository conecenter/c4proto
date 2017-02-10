package ee.cone.c4gate

import ee.cone.c4gate.TestFilterProtocol.Content
import ee.cone.c4proto.{Id, Protocol, protocol}

case object ContentValueText extends TextInputLens[Content](_.value,v⇒_.copy(value=v))

@protocol object TestFilterProtocol extends Protocol {
  @Id(0x0005) case class Content(
    @Id(0x0006) sessionKey: String,
    @Id(0x0007) value: String
  )
}

