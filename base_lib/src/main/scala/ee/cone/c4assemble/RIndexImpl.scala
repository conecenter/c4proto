package ee.cone.c4assemble

import ee.cone.c4assemble.RIndexTypes._

import java.util
import java.util.function.{BinaryOperator, Predicate}
import java.util.Comparator

final class RIndexImpl(
  val options: RIndexOptions,
  val data: Array[RIndexBucket],
) extends RIndex

final class RIndexBucket(
  val data: Array[RIndexItem], val index: InnerIndex,
  val subData: Array[RIndexItem], val subIndex: InnerIndex,
)

sealed trait InnerIndex {
  def ends(pos: Int): Int
  def starts(pos: Int): Int = if(pos == 0) 0 else ends(pos-1)
}
object EmptyInnerIndex extends InnerIndex {
  def ends(pos: Int): Int = 0
}
final class SmallInnerIndex(aEnds: Array[Byte]) extends InnerIndex {
  def ends(pos: Int): Int = aEnds(pos)
}
final class DefInnerIndex(aEnds: Array[Char]) extends InnerIndex {
  def ends(pos: Int): Int = aEnds(pos)
}
final class BigInnerIndex(aEnds: Array[Int]) extends InnerIndex {
  def ends(pos: Int): Int = aEnds(pos)
}

final class RIndexSeq(values: Array[RIndexItem], val start: Int, val length: Int) extends  IndexedSeq[RIndexItem] {
  def apply(i: Int): RIndexItem = values(start+i)
}

final class IndexItemComparator(util: RIndexUtilImpl, toKey: RIndexItem=>RIndexKey) extends Comparator[RIndexItem] {
  def compare(x: RIndexItem, y: RIndexItem): Int = util.compareKeys(toKey(x), toKey(y))
}

final class RIndexOptions(val power: Int, val toKey: RIndexItem => RIndexKey, val comparator: Comparator[RIndexItem])

final class RIndexUtilImpl(
  emptyBucket: RIndexBucket = new RIndexBucket(
    Array.empty, EmptyInnerIndex, Array.empty, EmptyInnerIndex,
  ),
) extends RIndexUtil {
  type Key = RIndexKey
  type IndexBucket = RIndexBucket

  def getHash(s: Key): Int = Integer.reverseBytes(s.hashCode)
  def compareKeys(xK: Key, yK: Key): Int = if(xK eq yK) 0 else {
    val xH = getHash(xK)
    val yH = getHash(yK)
    if (xH < yH) -1 else if (xH > yH) 1 else (xK:Object) match {
      case xS: String => (yK:Object) match {
        case yS: String => xS.compareTo(yS)
      }
    }
  }

  def keyToPosInRoot(power: Int, key: Key): Int = {
    val hash = getHash(key)
    (hash >> (Integer.SIZE - power)) + (1 << (power - 1))
  }

  def toPosInRoot(options: RIndexOptions)(pair: Pair): Int =
    keyToPosInRoot(options.power, pair._1)

  def getSizes(sz: Int, src: Array[Pair], toPos: Pair=>Int): Array[Int] = {
    val sizes = new Array[Int](sz)
    for (item <- src) sizes(toPos(item)) += 1
    sizes
  }

  def spread(src: Array[Pair], ends: Array[Int], toPos: Pair=>Int, toDest: Int=>Array[Pair]): Array[Int] = {
    val starts = ends.clone()
    for (item <- src){
      val pos = toPos(item)
      starts(pos) -= 1
      toDest(pos)(starts(pos)) = item
    }
    starts
  }

  def build(power: Int, toKey: RIndexItem=>RIndexKey, srcI: Iterator[Pair]): RIndex = {
    val comparator = new IndexItemComparator(this, toKey)
    val options = new RIndexOptions(power,toKey,comparator)
    val src = srcI.toArray
    val size = 1 << options.power
    val sizes = getSizes(size,src,toPosInRoot(options))
    val dest: Array[Array[Pair]] =
      sizes.map(s=>if(s==0) Array.empty else new Array(s))
    spread(src, sizes, toPosInRoot(options), dest)
    wrapData(options, dest.map(toBucketFromUnsorted(options,_)))
  }

  def getEnds(sizes: Array[Int]): Array[Int] = {
    val ends = new Array[Int](sizes.length)
    for (i <- sizes.indices) ends(i) = starts(ends,i) + sizes(i)
    ends
  }
  def starts(ends: Array[Int], i: Int): Int = if(i==0) 0 else ends(i-1)

  def makeInnerIndex(options: RIndexOptions, src: Array[Pair]): (Array[Int],Pair=>Int) = {
    val innerPower = Integer.SIZE - Integer.numberOfLeadingZeros(src.length)//+1
    val toPos: Pair=>Int = pair => keyToInnerPos(options.power,src.length,pair._1)
    val ends = getEnds(getSizes(1 << innerPower, src, toPos))
    assert(ends.last==src.length)
    (ends,toPos)
  }

  def toBucketFromUnsorted(options: RIndexOptions, src: Array[Pair]): IndexBucket =
    if(src.length==0) emptyBucket else {
      val (ends,toPos) = makeInnerIndex(options,src)
      val fatalComparator: Comparator[Pair] = (p1: Pair, p2: Pair) => {
        val r = compareKeys(p1._1, p2._1)
        if (r == 0) throw new Exception(s"equal not allowed: $p1 $p2")
        r
      }
      val dest = new Array[Pair](src.length)
      val starts = spread(src, ends, toPos, _=>dest)
      for (i <- ends.indices){
        val from = starts(i)
        val to = ends(i)
        if(to-from > 1) util.Arrays.sort(dest, from, to, fatalComparator)
      }
      toBucket(options, dest, ends)
    }

  def compressIndex(ends: Array[Int]): InnerIndex =
    if(ends.last <= Byte.MaxValue) new SmallInnerIndex(ends.map(_.toByte))
    else if(ends.last <= Char.MaxValue) new DefInnerIndex(ends.map(_.toChar))
    else new BigInnerIndex(ends)

  def keyToInnerPos(rootPower: Int, dataSize: Int, key: Key): Int = {
    val hash = getHash(key)
    val innerPower = Integer.SIZE - Integer.numberOfLeadingZeros(dataSize)
    (hash >> (Integer.SIZE - rootPower - innerPower)) & ((1 << innerPower)-1)
  }

  def wrapData(options: RIndexOptions, data: Array[IndexBucket]): RIndex =
    if(data.forall(isEmpty)) EmptyRIndex else new RIndexImpl(options, data)

  def isEmpty(r: IndexBucket): Boolean = r.data.length == 0
  def isEmpty(index: RIndex): Boolean = index eq EmptyRIndex

  def merge(aIndex: RIndex, bIndex: RIndex, mergeItems: (Seq[RIndexItem],Seq[RIndexItem])=>Seq[RIndexItem]): RIndex = (aIndex,bIndex) match {
    case (a,b) if isEmpty(a) => b
    case (a,b) if isEmpty(b) => a
    case (aI:RIndexImpl,bI:RIndexImpl) =>
      assert(aI.options.toKey eq bI.options.toKey, "merge toKey")
      val options = aI.options
      val aD = aI.data
      val bD = bI.data
      assert(aD.length == bD.length, "merge length")
      val nonEmptyP: Predicate[Pair] = _._2.nonEmpty
      val comparator: Comparator[Pair] =
        (p1: Pair, p2: Pair) => compareKeys(p1._1, p2._1)
      val mergePairs: BinaryOperator[Pair] = (a,b) => {
        val r = mergeItems(a._2,b._2)
        if(r eq a._2) a else if(r eq b._2) b else (a._1,r)
      }
      wrapData(options, Array.tabulate[IndexBucket](aD.length){ i =>
        val aR: IndexBucket = aD(i)
        val bR: IndexBucket = bD(i)
        if(isEmpty(aR)) bR else if(isEmpty(bR)) aR else {
          val work = new Array[Pair](aR.data.length+bR.data.length)
          val aE: Array[Pair] = getViews(aI.options,aR)
          val bE: Array[Pair] = getViews(bI.options,bR)
          val mergedSize = ArrayMerger.merge[Pair](aE,bE,work,comparator,mergePairs,nonEmptyP)
          if(mergedSize==0) emptyBucket else {
            val data = util.Arrays.copyOf(work, mergedSize)
            val (ends,toPos) = makeInnerIndex(options,data)
            for (i <- data.indices){
              val pos = toPos(data(i))
              assert(i >= starts(ends,pos) && i < ends(pos))
            }
            toBucket(options, data, ends)
          }
        }
      })
  }

  def get(index: RIndex, key: Key): Seq[RIndexItem] = index match {
    case a if isEmpty(a) => Nil
    case aI: RIndexImpl =>
      val bucket: IndexBucket = aI.data(keyToPosInRoot(aI.options.power,key))
      val iPos = keyToInnerPos(aI.options.power, bucket.data.length, key)
      val start = bucket.index.starts(iPos)
      val end = bucket.index.ends(iPos)
      val sz = end - start
      val comparator = aI.options.comparator
      // bucket.data can have key or value
      if(sz < 1) Nil
      else if(sz == 1){
        if(comparator.compare(asItem(key),bucket.data(start)) == 0)
          getValueView(bucket,start)
        else Nil
      }
      else {
        val found: Int =
          util.Arrays.binarySearch[RIndexItem](bucket.data, start, end, asItem(key), comparator)
        if(found>=0) getValueView(bucket,found) else Nil
      }

  }

  def asItem(key: Key): RIndexItem = key.asInstanceOf[RIndexItem]

  def keyIterator(index: RIndex): Iterator[RIndexKey] = index match {
    case a if isEmpty(a) => Iterator.empty
    case aI: RIndexImpl =>
      for {
        bucket <- aI.data.iterator
        o <- bucket.data
      } yield aI.options.toKey(o)
  }

  def toBucket(options: RIndexOptions, data: Array[Pair], ends: Array[Int]): IndexBucket = {
    val index = compressIndex(ends)
    val subSizes = data.map{
      case (key,Seq(value)) if key == options.toKey(value) => 0
      case (_,values) if values.nonEmpty => values.size
    }
    val subEnds = getEnds(subSizes)
    val rData: Array[RIndexItem] = Array.tabulate(data.length){ i =>
      val (k,v) = data(i)
      if(subSizes(i)>0) asItem(k) else v.head
    }
    if(subEnds.last==0)
      new RIndexBucket(rData, index, Array.empty, EmptyInnerIndex)
    else {
      val subIndex = compressIndex(subEnds)
      val subData = new Array[RIndexItem](subEnds.last)
      for(i <- data.indices) if(subSizes(i)>0) {
        val values = data(i)._2
        val dPos = subIndex.starts(i)
        val done = values.copyToArray(subData,dPos)
        assert(values.length == subIndex.ends(i) - dPos && done == values.length)
      }
      new RIndexBucket(rData, index, subData, subIndex)
    }
  }

  def getValueView(bucket: RIndexBucket, pos: Int): Seq[RIndexItem] = {
    val item = bucket.data(pos)
    val start = bucket.subIndex.starts(pos)
    val end = bucket.subIndex.ends(pos)
    val length = end - start
    if(length > 0) new RIndexSeq(bucket.subData,start,length) else item :: Nil
  }
  def getViews(options: RIndexOptions, bucket: RIndexBucket): Array[Pair] =
    Array.tabulate(bucket.data.length)(i=>options.toKey(bucket.data(i))->getValueView(bucket,i))

}

//arr.mapInPlace() arr.slice
// util.Arrays.copyOfRange(bucket.subData,from,to)


import scala.util.control.NonFatal
final class RIndexUtilDebug(inner: RIndexUtil=new RIndexUtilImpl()) extends RIndexUtil {
  def wrap[T](hint: String, f: =>T): T = try{
    f
  } catch {
    case NonFatal(e) =>
      println(s"failed in $hint",e.getMessage)
      e.printStackTrace()
      throw e
  }

  def get(index: RIndex, key: RIndexKey): Seq[RIndexItem] =
    wrap("get",inner.get(index,key))
  def merge(a: RIndex, b: RIndex, mergeItems: (Seq[RIndexItem],Seq[RIndexItem])=>Seq[RIndexItem]): RIndex =
    wrap("merge",inner.merge(a,b,mergeItems))
  def keyIterator(index: RIndex): Iterator[RIndexKey] =
    wrap("iterator",inner.keyIterator(index))
  def build(power: Int, toKey: RIndexItem=>RIndexKey, src: Iterator[Pair]): RIndex =
    wrap("build",inner.build(power, toKey, src))
}