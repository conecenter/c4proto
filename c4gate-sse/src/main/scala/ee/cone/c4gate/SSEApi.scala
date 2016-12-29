
package ee.cone.c4gate


import ee.cone.c4actor.Types.World
import ee.cone.c4gate.InternetProtocol.HttpPost
import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol object SSEProtocol extends Protocol {
  @Id(0x0030) case class InitDone(@Id(0x0031) connectionKey: String)
}

case class HttpPostByConnection(
    connectionKey: String,
    index: Int,
    headers: Map[String,String],
    request: HttpPost
)

trait SSEui {
  def allowOriginOption: Option[String]
  def fromAlien(post: HttpPostByConnection)(local: World): World
  def toAlien(tx: World): (World, List[(String, String)])
}