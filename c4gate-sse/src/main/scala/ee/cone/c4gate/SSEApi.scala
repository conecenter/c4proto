
package ee.cone.c4gate

import SSEProtocol.ConnectionPongState
import ee.cone.c4actor.{LEvent, TxTransform}
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4gate.InternetProtocol.TcpWrite

trait SSEMessages {
  def message(connectionKey: String, event: String, data: String, priority: Long): TcpWrite
}

@protocol object SSEProtocol extends Protocol {
  @Id(0x0030) case class ConnectionPingState(
    @Id(0x0031) connectionKey: String,
    @Id(0x0032) time: Long
  )
  @Id(0x0033) case class ConnectionPongState(
    @Id(0x0031) connectionKey: String,
    @Id(0x0032) time: Long,
    @Id(0x0034) sessionKey: String,
    @Id(0x0035) locationHash: String
  )
}

trait SSEConnection extends TxTransform {
  def connectionKey: String
  def pongState: Option[ConnectionPongState]
}