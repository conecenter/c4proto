
package ee.cone.c4gate

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble.WorldKey
import ee.cone.c4gate.BranchProtocol.{BranchResult, BranchSeed}
import ee.cone.c4gate.InternetProtocol.HttpPost
import ee.cone.c4proto
import ee.cone.c4proto.{Id, protocol}


case object AllowOriginKey extends WorldKey[Option[String]](None)
case object PostURLKey extends WorldKey[Option[String]](None)


case object AlienExchangeKey extends WorldKey[BranchTask ⇒ World ⇒ World](_⇒identity)

case class RichHttpPost(
  srcId: String,
  index: Long,
  headers: Map[String,String],
  request: HttpPost
)

case class BranchTask(
  branchKey: String,
  seed: BranchSeed,
  posts: List[RichHttpPost],
  connectionKeys: Set[SrcId],
  branchResults: Values[BranchResult]
)
//todo .andThen(RichHttpPosts(task.posts).remove)

@protocol object BranchProtocol extends c4proto.Protocol { //todo reg
  case class Subscription(
    @Id(???) connectionKey: SrcId
  )
  case class BranchSeed(
    @Id(???) hash: String,
    @Id(???) valueTypeId: Long,
    @Id(???) value: okio.ByteString
  )
  @Id(???) case class BranchResult(
    @Id(???) srcId: String,
    @Id(???) children: List[BranchSeed],
    @Id(???) subscriptions: List[Subscription]
  )

  @Id(???) case class FromAlien(
    @Id(???) connectionKey: SrcId,
    @Id(???) sessionKey: SrcId,
    @Id(???) locationSearch: String,
    @Id(???) locationHash: String
  )

}

trait SSEMessage {
  def message(connectionKey: String, event: String, data: String): World ⇒ World
}