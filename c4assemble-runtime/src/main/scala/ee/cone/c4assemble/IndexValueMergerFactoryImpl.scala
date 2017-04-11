package ee.cone.c4assemble

import ee.cone.c4assemble.TreeAssemblerTypes.MultiSet
import ee.cone.c4assemble.Types.Values

import scala.annotation.tailrec
import scala.collection.immutable.{Iterable, Map, Seq, TreeMap}

class ValueMerging[R<:Product](
  val addToMultiMap: PatchMap[R,Int,Int] = new PatchMap[R,Int,Int](0,_==0,(v,d)⇒v+d)
) {
  def toPrimaryKey(node: Product): String = node.productElement(0) match {
    case s: String ⇒ s
    case _ ⇒ throw new Exception(s"1st field of ${node.getClass.getName} should be primary key")
  }
  def fill(from: (R,Int)): Iterable[R] = {
    val(node,count) = from
    if(count<0) throw new Exception(s"$node gets negative count")
    List.fill(count)(node)
  }
  def toList(multiSet: MultiSet[R]): List[R] =
    multiSet.flatMap(fill).toList.sortBy(toPrimaryKey)
}

class SimpleIndexValueMergerFactory extends IndexValueMergerFactory {
  def create[R <: Product]: (Values[R],MultiSet[R]) ⇒ Values[R] = {
    val merging = new ValueMerging[R]
    (value,delta) ⇒ merging.toList(merging.addToMultiMap.many(delta, value, 1))
  }
}

case class CachingSeq[R](list: List[R], multiSet: MultiSet[R]) extends Seq[R] {
  def length: Int = list.size
  def apply(idx: Int): R = list(idx)
  def iterator: Iterator[R] = list.iterator
  override def toList: List[R] = list
}

class CachingIndexValueMergerFactory(from: Int) extends IndexValueMergerFactory {
  def create[R <: Product]: (Values[R], MultiSet[R]) ⇒ Values[R] = {
    val merging = new ValueMerging[R]
    (value,delta) ⇒
      val multiSet: MultiSet[R] = value match {
        case value: CachingSeq[R] ⇒ merging.addToMultiMap.many(value.multiSet, delta)
        case _ ⇒ merging.addToMultiMap.many(delta, value, 1)
      }
      val list = merging.toList(multiSet)
      if(list.size < from) list else CachingSeq(list,multiSet)
  }
}

case class TreeSeq[V](orig: TreeMap[(String,Int),V]) extends Seq[V] {
  def length: Int = orig.size
  def apply(idx: Int): V = orig.view(idx,idx+1).head._2
  def iterator: Iterator[V] = orig.iterator.map(_._2)
}

class TreeIndexValueMergerFactory(from: Int) extends IndexValueMergerFactory {
  def create[R <: Product]: (Values[R], MultiSet[R]) ⇒ Values[R] = {
    val merging = new ValueMerging[R]
    def patch(value: TreeSeq[R], delta: MultiSet[R]): TreeSeq[R] = TreeSeq(
      (value.orig /: delta.groupBy{ case (element,_) ⇒ merging.toPrimaryKey(element)}){
        (orig, dPair) ⇒
        val (prefix,dMMap) = dPair
        @tailrec def splitOne(index: Int, from: TreeMap[(String,Int),R], to: Map[R,Int]): TreeMap[(String,Int),R] = {
          val key = (prefix,index)
          val value = from.get(key)
          if(value.nonEmpty) splitOne(index+1, from - key, merging.addToMultiMap.one(to,value.get,1))
          else from ++ to.flatMap(merging.fill).toList.zipWithIndex.map{ case (el,i) ⇒ ((prefix,i),el) }
        }
        splitOne(0, orig, dMMap)
      }
    )
    (value,delta) ⇒ value match {
      case v: TreeSeq[R] ⇒ patch(v, delta)
      case _ ⇒
        val all = merging.addToMultiMap.many(delta, value, 1)
        if(all.values.sum < from) merging.toList(all) else patch(TreeSeq(TreeMap.empty), all)
    }
  }
}

// if we need more: scala rrb vector, java...binarySearch
// also consider: http://docs.scala-lang.org/overviews/collections/performance-characteristics.html