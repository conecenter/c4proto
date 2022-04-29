package ee.cone.c4assemble

import scala.reflect.ClassTag

trait RIndex
object EmptyRIndex extends RIndex

trait RIndexUtil[Key,Item] {
  def isEmpty(index: RIndex): Boolean
  def get(index: RIndex, key: Key): Item
  def merge(a: RIndex, b: RIndex): RIndex
  def iterator(index: RIndex): Iterator[Item]
  def build(src: Seq[Item]): RIndex
}

trait RIndexOptions[Key,Item]{
  def power: Int
  def toKey(item: Item): Key
  def merge(a: Item, b: Item): Item
  def empty: Item
  def toSearchItem(key: Key): Item
  def getData(index: RIndex): Array[IndexBucket[Item]]
  def wrapData(data: Array[IndexBucket[Item]]): RIndex
}

trait RIndexUtilFactory {
  def create[Key<:Object,Item<:Object:ClassTag](options: RIndexOptions[Key,Item]): RIndexUtil[Key,Item]
}
