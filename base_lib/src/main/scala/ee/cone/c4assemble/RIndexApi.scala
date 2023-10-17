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

trait IndexingTask {
  def subTasks: Seq[IndexingSubTask]
}
trait IndexingSubTask
trait IndexingResult

trait RIndexUtil {
  def get(index: RIndex, key: RIndexKey): Seq[RIndexItem]
  def nonEmpty(index: RIndex, key: RIndexKey): Boolean
  def isEmpty(index: RIndex): Boolean

  def buildIndex(power: Int, src: Array[RIndexPair], valueOperations: RIndexValueOperations): IndexingTask
  def mergeIndex(aIndex: RIndex, bIndex: RIndex, valueOperations: RIndexValueOperations): IndexingTask
  def execute(subTask: IndexingSubTask): IndexingResult
  def merge(task: IndexingTask, parts: Seq[IndexingResult]): RIndex

  def subIndexKeys(index: RIndex, partPos: Int, partCount: Int): Array[RIndexKey]
  def keyIterator(index: RIndex): Iterator[RIndexKey]
  def keyCount(index: RIndex): Int
  def valueCount(index: RIndex): Int
  def eqBuckets(a: RIndex, b: RIndex, key: RIndexKey): Boolean
  def changed(values: Seq[RIndexItem], diff: Seq[RIndexItem], valueOperations: RIndexValueOperations): Array[RIndexItem]
  def unchanged(values: Seq[RIndexItem], diff: Seq[RIndexItem], valueOperations: RIndexValueOperations): Array[RIndexItem]
}

trait RIndexValueOperations {
  //def compareInPairs(a: RIndexPair, b: RIndexPair): Int
  def compare(a: RIndexItem, b: RIndexItem): Int
  def merge(a: RIndexItem, b: RIndexItem): RIndexItem
  def nonEmpty(value: RIndexItem): Boolean
}

trait RIndexPair {
  def rIndexKey: RIndexKey
  def rIndexItem: RIndexItem
}