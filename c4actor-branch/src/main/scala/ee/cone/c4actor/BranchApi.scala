
package ee.cone.c4actor

import ee.cone.c4actor.BranchProtocol.BranchResult
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.World
import ee.cone.c4assemble.WorldKey
import ee.cone.c4proto._

object BranchTypes {
  type BranchKey = SrcId
}

trait BranchHandler extends Product {
  def exchange: (String⇒String) ⇒ World ⇒ World
  def seeds: World ⇒ List[BranchResult]
}

trait BranchTask extends Product {
  def branchKey: SrcId
  def product: Product
  def sessionKeys: World ⇒ Set[SrcId]
}

trait MessageFromAlien extends Product {
  def srcId: String
  def index: Long
  def headers: Map[String,String]
  def rm: World ⇒ World
}

trait BranchOperations {
  def toSeed(value: Product): BranchResult
  def toRel(seed: BranchResult, parentSrcId: SrcId, parentIsSession: Boolean): (SrcId,BranchRel)
}

case class BranchRel(srcId: SrcId, seed: BranchResult, parentSrcId: SrcId, parentIsSession: Boolean)

@protocol object BranchProtocol extends Protocol {
  @Id(0x0040) case class BranchResult(
    @Id(0x0041) hash: String,
    @Id(0x0042) valueTypeId: Long,
    @Id(0x0043) value: okio.ByteString,
    @Id(0x0044) children: List[BranchResult],
    @Id(0x0045) position: String
  )
}

case object SendToAlienKey extends WorldKey[(String,String,String)⇒World⇒World]((_,_,_)⇒throw new Exception)
