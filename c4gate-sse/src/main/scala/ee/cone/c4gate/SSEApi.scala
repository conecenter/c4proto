
package ee.cone.c4gate


import ee.cone.c4actor.Types.World
import ee.cone.c4gate.InternetProtocol.HttpPost

case class HttpPostByConnection(
    connectionKey: String,
    index: Int,
    headers: Map[String,String],
    request: HttpPost
)

trait SSEui {
  def allowOriginOption: Option[String]
  def postURL: String
  def fromAlien: (String⇒Option[String]) ⇒ World ⇒ World
  def toAlien: World ⇒ (World, List[(String, String)])
}
