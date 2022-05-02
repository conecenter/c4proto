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
  type Pair = (RIndexKey,Seq[RIndexItem])
  def get(index: RIndex, key: RIndexKey): Seq[RIndexItem]
  def merge(a: RIndex, b: RIndex, mergeItems: (Seq[RIndexItem],Seq[RIndexItem])=>Seq[RIndexItem]): RIndex
  def keyIterator(index: RIndex): Iterator[RIndexKey]
  def build(power: Int, toKey: RIndexItem=>RIndexKey, src: Iterator[Pair]): RIndex
}
