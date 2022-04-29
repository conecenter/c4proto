package ee.cone.c4assemble

//import ee.cone.c4assemble.RIndexTypes.OItem

import java.util
import java.util.function.Predicate
import java.util.Comparator
import scala.reflect.ClassTag
import scala.util.control.NonFatal

final class IndexBucket[OItem](val data: Array[OItem], val index: InnerIndex)

sealed trait InnerIndex
final class DefInnerIndex(val ends: Array[Char]) extends InnerIndex
final class BigInnerIndex(val ends: Array[Int]) extends InnerIndex

object RIndexTypes {
  //type OItem = Object
}

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
  def create[Key<:Object,Item<:Object:ClassTag](options: RIndexOptions[Key,Item]): RIndexUtil[Key,Item] = {
    val emptyBucket = new IndexBucket[Item](Array.empty[Item],new DefInnerIndex(Array.empty))
    new RIndexUtilDebug(new RIndexUtilImpl[Key,Item](options, emptyBucket)())
  }
}

final class RIndexUtilDebug[Key<:Object,OItem<:Object:ClassTag](inner: RIndexUtil[Key,OItem]) extends RIndexUtil[Key,OItem] {
  def wrap[T](hint: String, f: =>T): T = try{
    f
  } catch {
    case NonFatal(e) =>
      println(s"failed in $hint",e.getMessage)
      e.printStackTrace()
      throw e
  }

  def isEmpty(index: RIndex): Boolean = wrap("isEmpty",inner.isEmpty(index))
  def get(index: RIndex, key: Key): OItem = wrap("get",inner.get(index,key))
  def merge(
    a: RIndex,
    b: RIndex
  ): RIndex = wrap("merge",inner.merge(a,b))
  def iterator(index: RIndex): Iterator[OItem] = wrap("iterator",inner.iterator(index))
  def build(src: Seq[OItem]): RIndex = wrap("build",inner.build(src))
}

final class RIndexUtilImpl[Key<:Object,OItem<:Object:ClassTag](
  options: RIndexOptions[Key,OItem], emptyBucket: IndexBucket[OItem]
)(
  comparator: IndexItemComparator[Key,OItem] = new IndexItemComparator
  (options.toKey),
  fatalComparator: Comparator[OItem] = new FatalComparator(new IndexItemComparator(options.toKey))
) extends RIndexUtil[Key,OItem] {
  def size: Int = 1 << options.power

  def keyToPosInRoot(key: Key): Int = {
    val hash = comparator.hash(key)
    (hash >> (Integer.SIZE - options.power)) + (1 << (options.power - 1))
  }

  def toPosInRoot(item: OItem): Int = keyToPosInRoot(options.toKey(item))

  def getSizes(sz: Int, src: Array[OItem], toPos: OItem=>Int): Array[Int] = {
    val sizes = new Array[Int](sz)
    for (item <- src) sizes(toPos(item)) += 1
    sizes
  }

  def spread(src: Array[OItem], ends: Array[Int], toPos: OItem=>Int, toDest: Int=>Array[OItem]): Array[Int] = {
    val starts = ends.clone()
    for (item <- src){
      val pos = toPos(item)
      starts(pos) -= 1
      toDest(pos)(starts(pos)) = item
    }
    starts
  }

  def build(srcSeq: Seq[OItem]): RIndex = {
    val src = srcSeq.toArray
    assert(src.forall(_ ne options.empty))
    val sizes = getSizes(size,src,toPosInRoot)
    val dest: Array[Array[OItem]] =
      sizes.map(s=>if(s==0) Array.empty else new Array(s))
    spread(src, sizes, toPosInRoot, dest)
    wrapData(dest.map(toBucketFromUnsorted))
  }

  def makeInnerIndex(src: Array[OItem]): (Array[Int],OItem=>Int) = {
    val innerPower = Integer.SIZE - Integer.numberOfLeadingZeros(src.length)//+1
    val toPos: OItem=>Int = item => keyToInnerPos(src.length,options.toKey(item))
    val ends = getSizes(1 << innerPower, src, toPos)
    for (i <- 1 until ends.length) ends(i) += ends(i-1) // sizes to ends
    assert(ends(ends.length-1)==src.length)
    (ends,toPos)
  }

  def toBucketFromUnsorted(src: Array[OItem]): IndexBucket[OItem] =
    if(src.length==0) emptyBucket else {
      val (ends,toPos) = makeInnerIndex(src)
      val dest = new Array[OItem](src.length)
      val starts = spread(src, ends, toPos, _=>dest)
      for (i <- ends.indices){
        val from = starts(i)
        val to = ends(i)
        if(to-from > 1) util.Arrays.sort(dest, from, to, fatalComparator)
      }
      toBucket(dest, ends)
    }

  def toBucket(data: Array[OItem], ends: Array[Int]): IndexBucket[OItem] =
    if(data.length <= Character.MAX_VALUE)
      new IndexBucket(data, new DefInnerIndex(ends.map(_.toChar)))
    else new IndexBucket(data, new BigInnerIndex(ends))


  def keyToInnerPos(dataSize: Int, key: Key): Int = {
    val hash = comparator.hash(key)
    val innerPower = Integer.SIZE - Integer.numberOfLeadingZeros(dataSize)
    (hash >> (Integer.SIZE - options.power - innerPower)) & ((1 << innerPower)-1)
  }

  def wrapData(data: Array[IndexBucket[OItem]]): RIndex =
    if(data.forall(isEmpty)) EmptyRIndex else options.wrapData(data)

  def isEmpty(r: IndexBucket[_]): Boolean = r.data.length == 0
  def isEmpty(index: RIndex): Boolean = index eq EmptyRIndex

  def merge(aIndex: RIndex, bIndex: RIndex): RIndex = (aIndex,bIndex) match {
    case (a,b) if isEmpty(a) => b
    case (a,b) if isEmpty(b) => a
    case (aI,bI) =>
      val aD = options.getData(aI)
      val bD = options.getData(bI)
      assert(aD.length == bD.length)
      val data: Array[IndexBucket[OItem]] =
        new Array[IndexBucket[OItem]](aD.length)
      val maxBucketSize = aD.indices.iterator
        .map(i=>aD(i).data.length+bD(i).data.length).max
      val work = new Array[OItem](maxBucketSize)
      val nonEmptyP: Predicate[OItem] = (t: OItem) => t ne options.empty
      for(i <- data.indices) {
        val aR: IndexBucket[OItem] = aD(i)
        val bR: IndexBucket[OItem] = bD(i)
        data(i) = if(isEmpty(aR)) bR else if(isEmpty(bR)) aR else {
          val mergedSize = ArrayMerger.merge(aR.data,bR.data,work,comparator,options.merge,nonEmptyP)
          if(mergedSize==0) emptyBucket else {
            val data = util.Arrays.copyOf(work, mergedSize)
            val (ends,_) = makeInnerIndex(data)
            toBucket(data, ends)
          }
        }
      }
      wrapData(data)
  }

  def getEnd(bucket: IndexBucket[OItem], pos: Int): Int = bucket.index match {
    case b: DefInnerIndex => b.ends(pos)
    case b: BigInnerIndex => b.ends(pos)
  }

  def get(index: RIndex, key: Key): OItem = index match {
    case a if isEmpty(a) => options.empty
    case aI =>
      val bucket: IndexBucket[OItem] = options.getData(aI)(keyToPosInRoot(key))
      if(bucket.data.length==0) options.empty else {
        val iPos = keyToInnerPos(bucket.data.length, key)
        val from = if(iPos>0) getEnd(bucket,iPos-1) else 0
        val to = getEnd(bucket,iPos)
        val sz = to - from
        if(sz < 1) options.empty
        else if(sz < 2){
          val item = bucket.data(from)
          if(comparator.compareKeys(key,options.toKey(item)) == 0) item else options.empty
        }
        else {
          val iPos: Int = util.Arrays.binarySearch[OItem](bucket.data, from, to, options.toSearchItem(key), comparator: Comparator[OItem])
          if(iPos>=0) bucket.data(iPos) else options.empty
        }
      }
  }

  def iterator(index: RIndex): Iterator[OItem] = index match {
    case a if isEmpty(a) => Iterator.empty
    case aI => options.getData(aI).iterator.flatMap(_.data.iterator)
  }
}
