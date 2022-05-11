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

trait RIndexUtil {
  def get(index: RIndex, key: RIndexKey): Seq[RIndexItem]
  def merge(a: RIndex, b: RIndex, valueOperations: RIndexValueOperations): RIndex
  def keyIterator(index: RIndex): Iterator[RIndexKey]
  def build(power: Int, src: Array[RIndexPair], valueOperations: RIndexValueOperations): RIndex
  def eqBuckets(a: RIndex, b: RIndex, key: RIndexKey): Boolean
}

trait RIndexValueOperations {
  def compareInPairs(a: RIndexPair, b: RIndexPair): Int
  def compare(a: RIndexItem, b: RIndexItem): Int
  def merge(a: RIndexItem, b: RIndexItem): RIndexItem
  def nonEmpty(value: RIndexItem): Boolean
}

trait RIndexPair {
  def rIndexKey: RIndexKey
  def rIndexItem: RIndexItem
}