
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
  def fromAlien(post: HttpPostByConnection)(local: World): World
  def toAlien(local: World): (World, List[(String, String)])
}