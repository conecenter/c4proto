
package ee.cone.c4gate


import ee.cone.c4actor.{WorldKey, WorldTx}
import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol object SSEProtocol extends Protocol {
  @Id(0x0030) case class InitDone(@Id(0x0031) connectionKey: String)
}

case object SSESendKey extends WorldKey[Option[SSESend]](None)
trait SSESend {
  def message(tx: WorldTx, event: String, data: String): WorldTx
  def relocate(tx: WorldTx, value: String): WorldTx
}

class SSEConfig(
  val allowOriginOption: Option[String],
  val alienExchange: WorldTx⇒WorldTx
)