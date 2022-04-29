package ee.cone.c4assemble

import ee.cone.c4assemble.RIndexTypes.RIndexItem

import java.util
import java.util.function.{BinaryOperator, Predicate}
import java.util.Comparator
//import scala.util.control.NonFatal

final class IndexBucket(val data: Array[RIndexItem], val index: InnerIndex)

sealed trait InnerIndex
final class SmallInnerIndex(val ends: Array[Byte]) extends InnerIndex
final class DefInnerIndex(val ends: Array[Char]) extends InnerIndex
final class BigInnerIndex(val ends: Array[Int]) extends InnerIndex

final class IndexItemComparator[Key<:Object,Item](toKey: Item=>Key) extends Comparator[Item] {
  def hash(s: Key): Int = Integer.reverseBytes(s.hashCode)
  def compareKeys(xK: Key, yK: Key): Int = if(xK eq yK) 0 else {
    val xH = hash(xK)
    val yH = hash(yK)
    if (xH < yH) -1 else if (xH > yH) 1 else xK match {
      case xS: String => yK match {
        case yS: String => xS.compareTo(yS)
      }
    }
  }
  def compare(x: Item, y: Item): Int = compareKeys(toKey(x), toKey(y))
}

final class FatalComparator[T](inner: Comparator[T]) extends Comparator[T] {
  def compare(o1: T, o2: T): Int = {
    val r = inner.compare(o1,o2)
    if(r==0) throw new Exception(s"equal not allowed: $o1 $o2")
    r
  }
}

class RIndexUtilFactoryImpl extends RIndexUtilFactory {
  def create[Key<:Object](options: RIndexOptions[Key]): RIndexUtil[Key] = {
    val emptyBucket = new IndexBucket(Array.empty[RIndexItem],new DefInnerIndex(Array.empty))
    /*new RIndexUtilDebug(*/new RIndexUtilImpl[Key](options, emptyBucket)()
  }
}

/*
final class RIndexUtilDebug[Key<:Object](inner: RIndexUtil[Key]) extends RIndexUtil[Key] {
  def wrap[T](hint: String, f: =>T): T = try{
    f
  } catch {
    case NonFatal(e) =>
      println(s"failed in $hint",e.getMessage)
      e.printStackTrace()
      throw e
  }

  def isEmpty(index: RIndex): Boolean = wrap("isEmpty",inner.isEmpty(index))
  def get(index: RIndex, key: Key): RIndexItem = wrap("get",inner.get(index,key))
  def merge(
    a: RIndex,
    b: RIndex
  ): RIndex = wrap("merge",inner.merge(a,b))
  def iterator(index: RIndex): Iterator[RIndexItem] = wrap("iterator",inner.iterator(index))
  def build(src: Seq[RIndexItem]): RIndex = wrap("build",inner.build(src))
}*/

final class RIndexUtilImpl[Key<:Object](
  options: RIndexOptions[Key], emptyBucket: IndexBucket
)(
  comparator: IndexItemComparator[Key,RIndexItem] = new IndexItemComparator
  (options.toKey),
  fatalComparator: Comparator[RIndexItem] = new FatalComparator(new IndexItemComparator(options.toKey))
) extends RIndexUtil[Key] {
  def emptyItem: RIndexItem = EmptyRIndexItem.asInstanceOf[RIndexItem]

  def size: Int = 1 << options.power

  def keyToPosInRoot(key: Key): Int = {
    val hash = comparator.hash(key)
    (hash >> (Integer.SIZE - options.power)) + (1 << (options.power - 1))
  }

  def toPosInRoot(item: RIndexItem): Int = keyToPosInRoot(options.toKey(item))

  def getSizes(sz: Int, src: Array[RIndexItem], toPos: RIndexItem=>Int): Array[Int] = {
    val sizes = new Array[Int](sz)
    for (item <- src) sizes(toPos(item)) += 1
    sizes
  }

  def spread(src: Array[RIndexItem], ends: Array[Int], toPos: RIndexItem=>Int, toDest: Int=>Array[RIndexItem]): Array[Int] = {
    val starts = ends.clone()
    for (item <- src){
      val pos = toPos(item)
      starts(pos) -= 1
      toDest(pos)(starts(pos)) = item
    }
    starts
  }

  def build(srcSeq: Seq[RIndexItem]): RIndex = {
    val src = srcSeq.toArray
    assert(src.forall(_ ne emptyItem))
    val sizes = getSizes(size,src,toPosInRoot)
    val dest: Array[Array[RIndexItem]] =
      sizes.map(s=>if(s==0) Array.empty else new Array(s))
    spread(src, sizes, toPosInRoot, dest)
    wrapData(dest.map(toBucketFromUnsorted))
  }

  def makeInnerIndex(src: Array[RIndexItem]): (Array[Int],RIndexItem=>Int) = {
    val innerPower = Integer.SIZE - Integer.numberOfLeadingZeros(src.length)//+1
    val toPos: RIndexItem=>Int = item => keyToInnerPos(src.length,options.toKey(item))
    val ends = getSizes(1 << innerPower, src, toPos)
    for (i <- 1 until ends.length) ends(i) += ends(i-1) // sizes to ends
    assert(ends(ends.length-1)==src.length)
    (ends,toPos)
  }

  def toBucketFromUnsorted(src: Array[RIndexItem]): IndexBucket =
    if(src.length==0) emptyBucket else {
      val (ends,toPos) = makeInnerIndex(src)
      val dest = new Array[RIndexItem](src.length)
      val starts = spread(src, ends, toPos, _=>dest)
      for (i <- ends.indices){
        val from = starts(i)
        val to = ends(i)
        if(to-from > 1) sort(dest, from, to, fatalComparator)
      }
      toBucket(dest, ends)
    }

  def toBucket(data: Array[RIndexItem], ends: Array[Int]): IndexBucket =
    new IndexBucket(data,
      if(data.length <= Byte.MaxValue) new SmallInnerIndex(ends.map(_.toByte))
      else if(data.length <= Char.MaxValue) new DefInnerIndex(ends.map(_.toChar))
      else new BigInnerIndex(ends)
    )


  def keyToInnerPos(dataSize: Int, key: Key): Int = {
    val hash = comparator.hash(key)
    val innerPower = Integer.SIZE - Integer.numberOfLeadingZeros(dataSize)
    (hash >> (Integer.SIZE - options.power - innerPower)) & ((1 << innerPower)-1)
  }

  def wrapData(data: Array[IndexBucket]): RIndex =
    if(data.forall(isEmpty)) EmptyRIndex else options.wrapData(data)

  def isEmpty(r: IndexBucket): Boolean = r.data.length == 0
  def isEmpty(index: RIndex): Boolean = index eq EmptyRIndex

  def merge(aIndex: RIndex, bIndex: RIndex): RIndex = (aIndex,bIndex) match {
    case (a,b) if isEmpty(a) => b
    case (a,b) if isEmpty(b) => a
    case (aI,bI) =>
      val aD = options.getData(aI)
      val bD = options.getData(bI)
      assert(aD.length == bD.length)
      val data: Array[IndexBucket] =
        new Array[IndexBucket](aD.length)
      val maxBucketSize = aD.indices.iterator
        .map(i=>aD(i).data.length+bD(i).data.length).max
      val work = new Array[RIndexItem](maxBucketSize)
      val nonEmptyP: Predicate[RIndexItem] = (t: RIndexItem) => t ne emptyItem
      for(i <- data.indices) {
        val aR: IndexBucket = aD(i)
        val bR: IndexBucket = bD(i)
        data(i) = if(isEmpty(aR)) bR else if(isEmpty(bR)) aR else {
          val mergedSize = merge(aR.data,bR.data,work,comparator,options.merge,nonEmptyP)
          if(mergedSize==0) emptyBucket else {
            val data = copyOf(work, mergedSize)
            val (ends,_) = makeInnerIndex(data)
            toBucket(data, ends)
          }
        }
      }
      wrapData(data)
  }

  def merge[T](
    srcA: Array[T], srcB: Array[T], dest: Array[T],
    c: Comparator[T], merger: BinaryOperator[T], nonEmpty: Predicate[T]
  ): Int = ArrayMerger.merge(
    srcA.asInstanceOf[Array[Object]],
    srcB.asInstanceOf[Array[Object]],
    dest.asInstanceOf[Array[Object]],
    c.asInstanceOf[Comparator[Object]],
    merger.asInstanceOf[BinaryOperator[Object]],
    nonEmpty.asInstanceOf[Predicate[Object]]
  )
  def copyOf(a: Array[RIndexItem], sz: Int): Array[RIndexItem] =
    util.Arrays.copyOf(a.asInstanceOf[Array[Object]], sz).asInstanceOf[Array[RIndexItem]]
  def sort(a: Array[RIndexItem], from: Int, to: Int, comparator: Comparator[RIndexItem]): Unit =
    util.Arrays.sort(a.asInstanceOf[Array[Object]], from, to, comparator.asInstanceOf[Comparator[Object]])

  def getEnd(bucket: IndexBucket, pos: Int): Int = bucket.index match {
    case b: SmallInnerIndex => b.ends(pos)
    case b: DefInnerIndex => b.ends(pos)
    case b: BigInnerIndex => b.ends(pos)
  }

  def get(index: RIndex, key: Key): RIndexItem = index match {
    case a if isEmpty(a) => emptyItem
    case aI =>
      val bucket: IndexBucket = options.getData(aI)(keyToPosInRoot(key))
      if(bucket.data.length==0) emptyItem else {
        val iPos = keyToInnerPos(bucket.data.length, key)
        val from = if(iPos>0) getEnd(bucket,iPos-1) else 0
        val to = getEnd(bucket,iPos)
        val sz = to - from
        if(sz < 1) emptyItem
        else if(sz < 2){
          val item = bucket.data(from)
          if(comparator.compareKeys(key,options.toKey(item)) == 0) item else emptyItem
        }
        else {
          val iPos: Int = util.Arrays.binarySearch[RIndexItem](bucket.data, from, to, options.toSearchItem(key), comparator: Comparator[RIndexItem])
          if(iPos>=0) bucket.data(iPos) else emptyItem
        }
      }
  }

  def iterator(index: RIndex): Iterator[RIndexItem] = index match {
    case a if isEmpty(a) => Iterator.empty
    case aI => options.getData(aI).iterator.flatMap(_.data.iterator)
  }
}
