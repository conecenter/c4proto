package ee.cone.c4assemble

import ee.cone.c4assemble.RIndexTypes._

trait RIndex
object EmptyRIndex extends RIndex

object RIndexTypes {
  type Tagged[U] = { type Tag = U }
  sealed trait RIndexItemTag
  sealed trait RIndexKeyTag
  type RIndexKey = Object with Tagged[RIndexKeyTag]
  type RIndexItem = Object with Tagged[RIndexItemTag]
}

trait RecalculationTask
trait IndexingTask {
  def subTasks: Seq[IndexingSubTask]
}
trait IndexingSubTask
trait IndexingResult
trait AbstractProfilingCounts extends Product { def spentNs: Long }
case class MergeProfilingCounts(partCount: Long, spentNs: Long) extends AbstractProfilingCounts
trait RIndexUtil {
  def create(creatorPos: Int, key: RIndexKey, value: RIndexItem): RIndexPair
  def get(index: RIndex, key: RIndexKey): Seq[RIndexItem]
  def nonEmpty(index: RIndex, key: RIndexKey): Boolean
  def isEmpty(index: RIndex): Boolean

  def recalculate(diffs: Array[RIndex]): Array[RecalculationTask]
  def execute(task: RecalculationTask, handle: RIndexKey=>Unit): Unit

  def buildIndex(prev: Array[RIndex], src: Array[Array[RIndexPair]], valueOperations: RIndexValueOperations): IndexingTask
  def execute(subTask: IndexingSubTask): IndexingResult
  def merge(task: IndexingTask, parts: Array[IndexingResult]): (Seq[RIndex], MergeProfilingCounts)

  def keyIterator(index: RIndex): Iterator[RIndexKey]
  def keyCount(index: RIndex): Int
  def valueCount(index: RIndex): Int
  def eqBuckets(a: RIndex, b: RIndex, key: RIndexKey): Boolean
  def changed(values: Seq[RIndexItem], diff: Seq[RIndexItem], valueOperations: RIndexValueOperations): Array[RIndexItem]
  def unchanged(values: Seq[RIndexItem], diff: Seq[RIndexItem], valueOperations: RIndexValueOperations): Array[RIndexItem]
}

trait RIndexValueOperations {
  //def compareInPairs(a: RIndexPair, b: RIndexPair): Int
  def compare(a: RIndexItem, b: RIndexItem, cache: HashCodeCache): Int
  def merge(a: RIndexItem, b: RIndexItem): RIndexItem
  def nonEmpty(value: RIndexItem): Boolean
}

trait RIndexPair {
  def creatorPos: Int
  def rIndexItem: RIndexItem
}