package ee.cone.c4assemble

import ee.cone.c4assemble.RIndexTypes.RIndexItem

trait RIndex
object EmptyRIndex extends RIndex
object EmptyRIndexItem

sealed trait RIndexItemTag
object RIndexTypes {
  type Tagged[U] = { type Tag = U }
  type RIndexItem = Object with Tagged[RIndexItemTag]
}

trait RIndexUtil[Key] {
  def emptyItem: RIndexItem
  def isEmpty(index: RIndex): Boolean
  def get(index: RIndex, key: Key): RIndexItem
  def merge(a: RIndex, b: RIndex): RIndex
  def iterator(index: RIndex): Iterator[RIndexItem]
  def build(src: Seq[RIndexItem]): RIndex
}

trait RIndexOptions[Key]{
  def power: Int
  def toKey(item: RIndexItem): Key
  def merge(a: RIndexItem, b: RIndexItem): RIndexItem
  def toSearchItem(key: Key): RIndexItem
  def getData(index: RIndex): Array[IndexBucket]
  def wrapData(data: Array[IndexBucket]): RIndex
}

trait RIndexUtilFactory {
  def create[Key<:Object](options: RIndexOptions[Key]): RIndexUtil[Key]
}
