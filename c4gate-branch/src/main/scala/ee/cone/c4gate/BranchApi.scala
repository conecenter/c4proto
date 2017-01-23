
package ee.cone.c4gate

import ee.cone.c4actor.TxTransform
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.World
import ee.cone.c4gate.BranchProtocol.BranchResult
import ee.cone.c4proto._

//case object AlienExchangeKey extends WorldKey[BranchTask ⇒ World ⇒ World](_⇒identity)

trait BranchHandler extends Product {
  def exchange: (String⇒String) ⇒ World ⇒ World
  def seeds: World ⇒ List[BranchResult]
}

trait BranchSender {
  def branchKey: SrcId
  def sessionKeys: World ⇒ Set[SrcId]
  def send: (String, String, String) ⇒ World ⇒ World
}

trait BranchTask extends TxTransform {
  def sender: BranchSender
  def product: Product
  def withHandler(handler: BranchHandler): BranchTask
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
