
package ee.cone.c4actor_branch

import ee.cone.c4actor._
import ee.cone.c4actor_branch.BranchProtocol.N_BranchResult
import ee.cone.c4actor.Types.{LEvents, SrcId}
import ee.cone.c4actor_branch.BranchTypes.BranchKey
import ee.cone.c4proto._

object BranchTypes {
  type BranchKey = SrcId
}

trait BranchErrorSaver {
  def saveErrors(local: Context, branchKey: BranchKey, error: Throwable): Seq[LEvent[Product]]
}

trait BranchTask extends Product {
  def branchKey: SrcId
  def product: Product
  def relocate(to: String): Context => Context
}

trait BranchOperations {
  def toSeed(value: Product): N_BranchResult
  def collect[T<:Product](seeds: Seq[N_BranchResult], cl: Class[T]): Seq[T]
  def saveChanges(local: Context, branchKey: String, seeds: List[N_BranchResult]): LEvents
  def purge(local: Context, branchKey: String): LEvents
}

@protocol("BranchApp") object BranchProtocol   {
  @Id(0x0040) case class S_BranchResults(
    @Id(0x0041) branchKey: String,
    @Id(0x0044) children: List[N_BranchResult],
    // was fields 0x0042 0x0043 0x0045
  )
  case class N_BranchResult(
    @Id(0x0041) hash: String,
    @Id(0x0042) valueTypeId: Long,
    @Id(0x0043) value: okio.ByteString,
  )

  @Id(0x004B) case class U_Redraw(
    @Id(0x004C) srcId: String,
    @Id(0x004D) branchKey: String
  )

  @Id(0x004E) case class N_RestPeriod(
    @Id(0x004D) branchKey: String,
    @Id(0x004F) value: Long
  )
}

trait BranchError {
  def message(local: Context): String
}
