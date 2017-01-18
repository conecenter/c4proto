
package ee.cone.c4gate

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble.WorldKey
import ee.cone.c4gate.BranchProtocol.{BranchResult, BranchSeed}
import ee.cone.c4gate.HttpProtocol.HttpPost
import ee.cone.c4proto
import ee.cone.c4proto.{Id, protocol}





case object AlienExchangeKey extends WorldKey[BranchTask ⇒ World ⇒ World](_⇒identity)

case class RichHttpPost(
  srcId: String,
  index: Long,
  headers: Map[String,String],
  request: HttpPost
)

case class BranchTask(
  branchKey: String,
  seed: Option[DecodedBranchSeed],
  posts: List[RichHttpPost],
  sessionKeys: Set[SrcId],
  branchResults: Values[DecodedBranchResult]
)
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

trait AlienMessage {
  def message(sessionKey: String, event: String, data: String): World ⇒ World
}