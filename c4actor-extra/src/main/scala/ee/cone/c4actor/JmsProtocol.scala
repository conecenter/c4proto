package ee.cone.c4actor

import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol(UpdatesCat) object JmsProtocol extends Protocol {

  @Id(0x0a11) case class JmsMessage(
    @Id(0x0a0c) messageId: String,
    @Id(0x0a0b) jmsType: String,
    @Id(0x0acc) messageBody: String
  )

  @Id(0x0dab) case class JmsDoneMarker(
    @Id(0x0bad) srcId: String // messageId
  )
}
