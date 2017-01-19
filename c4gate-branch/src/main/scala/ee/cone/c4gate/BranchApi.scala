
package ee.cone.c4gate

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.World
import ee.cone.c4gate.BranchProtocol._
import ee.cone.c4proto
import ee.cone.c4proto.{Id, protocol}

//case object AlienExchangeKey extends WorldKey[BranchTask ⇒ World ⇒ World](_⇒identity)

trait BranchTask extends Product {
  def product: Product
  def getPosts: List[Map[String,String]]
  def sessionKeys: Set[SrcId]
  def updateResult(newChildren: List[BranchSeed]): World ⇒ World
  def rmPosts: World ⇒ World
  def message(sessionKey: String, event: String, data: String): World ⇒ World
}

//todo .andThen(RichHttpPosts(task.posts).remove)

@protocol object BranchProtocol extends c4proto.Protocol { //todo reg
  case class Subscription(
    @Id(???) sessionKey: String
  )
  case class BranchSeed(
    @Id(???) hash: String,
    @Id(???) valueTypeId: Long,
    @Id(???) value: okio.ByteString
  )
  @Id(???) case class BranchResult(
    @Id(???) hash: String,
    @Id(???) parent: Option[BranchSeed],
    @Id(???) children: List[BranchSeed],
    @Id(???) subscriptions: List[Subscription]
  )
}
