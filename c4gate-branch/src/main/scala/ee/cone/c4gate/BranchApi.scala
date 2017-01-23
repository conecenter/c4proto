
package ee.cone.c4gate

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.World
import ee.cone.c4gate.BranchProtocol.BranchResult
import ee.cone.c4proto._

//case object AlienExchangeKey extends WorldKey[BranchTask ⇒ World ⇒ World](_⇒identity)

trait BranchTask extends Product {
  def branchKey: SrcId
  def product: Product
  def getPosts: List[Map[String,String]]
  def sessionKeys: World ⇒ Set[SrcId]
  def updateResult(newChildren: List[BranchResult]): World ⇒ World
  def rmPosts: World ⇒ World
  def message(sessionKey: String, event: String, data: String): World ⇒ World
}

//todo .andThen(RichHttpPosts(task.posts).remove)
//todo reg

@protocol object BranchProtocol extends Protocol {
  @Id(0x0040) case class BranchResult(
    @Id(0x0041) hash: String,
    @Id(0x0042) valueTypeId: Long,
    @Id(0x0043) value: okio.ByteString,
    @Id(0x0044) children: List[BranchResult]
  )
}
