
package ee.cone.c4gate

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.World
import ee.cone.c4gate.BranchProtocol.BranchSeed
import ee.cone.c4proto._

//case object AlienExchangeKey extends WorldKey[BranchTask ⇒ World ⇒ World](_⇒identity)

trait BranchTask extends Product {
  def branchKey: SrcId
  def product: Product
  def getPosts: List[Map[String,String]]
  def sessionKeys: Set[SrcId]
  def updateResult(newChildren: List[BranchSeed]): World ⇒ World
  def rmPosts: World ⇒ World
  def message(sessionKey: String, event: String, data: String): World ⇒ World
}

//todo .andThen(RichHttpPosts(task.posts).remove)
//todo reg

@protocol object BranchProtocol extends Protocol {
  case class Subscription(
    @Id(0x0040) sessionKey: String
  )
  case class BranchSeed(
    @Id(0x0041) hash: String,
    @Id(0x0042) valueTypeId: Long,
    @Id(0x0043) value: okio.ByteString
  )
  @Id(0x0044) case class BranchResult(
    @Id(0x0041) hash: String,
    @Id(0x0045) parent: Option[BranchSeed],
    @Id(0x0046) children: List[BranchSeed],
    @Id(0x0047) subscriptions: List[Subscription]
  )
}
