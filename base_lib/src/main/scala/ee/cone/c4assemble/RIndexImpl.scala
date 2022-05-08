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
  val keys: Array[RIndexKey], val hashPartToKeyRange: InnerIndex,
  val values: Array[RIndexItem], val keyPosToValueRange: InnerIndex,
)

sealed trait InnerIndex {
  def ends(pos: Int): Int
  def starts(pos: Int): Int = if(pos == 0) 0 else ends(pos-1)
}
object EmptyInnerIndex extends InnerIndex {
  def ends(pos: Int): Int = 0
}
object OneToOneInnerIndex extends InnerIndex {
  def ends(pos: Int): Int = pos + 1
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
  override def copyToArray[B >: RIndexItem](dest: Array[B], destStart: Int): Int = {
    System.arraycopy(values, start, dest, destStart, length)
    length
  }
}

final class RIndexOptions(val power: Int, val keyComparator: Comparator[RIndexKey])

final class RIndexUtilImpl(
  emptyBucket: RIndexBucket = new RIndexBucket(
    Array.empty, EmptyInnerIndex, Array.empty, EmptyInnerIndex,
  ),
) extends RIndexUtil {

  def getHash(s: RIndexKey): Int = Integer.reverseBytes(s.hashCode)
  def compareKeys(xK: RIndexKey, yK: RIndexKey): Int = if(xK eq yK) 0 else {
    val xH = getHash(xK)
    val yH = getHash(yK)
    if (xH < yH) -1 else if (xH > yH) 1 else (xK:Object) match {
      case xS: String => (yK:Object) match {
        case yS: String => xS.compareTo(yS)
      }
    }
  }

  def keyToPosInRoot(power: Int, key: RIndexKey): Int = {
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

  def build(power: Int, srcI: Iterator[Pair]): RIndex = {
    val keyComparator: Comparator[RIndexKey] = compareKeys(_,_)
    val options = new RIndexOptions(power,keyComparator)
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

  def toBucketFromUnsorted(options: RIndexOptions, src: Array[Pair]): RIndexBucket =
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
      toBucket(dest, ends)
    }

  def compressIndex(ends: Array[Int]): InnerIndex = {
    if(ends.last == ends.length) OneToOneInnerIndex // because there's no empty
    else if(ends.last <= Byte.MaxValue){
      val res = new Array[Byte](ends.length)
      for(i <- ends.indices) res(i) = ends(i).toByte // .map boxes
      new SmallInnerIndex(res)
    }
    else if(ends.last <= Char.MaxValue){
      val res = new Array[Char](ends.length)
      for(i <- ends.indices) res(i) = ends(i).toChar
      new DefInnerIndex(res)
    }
    else new BigInnerIndex(ends)
  }

  def keyToInnerPos(rootPower: Int, dataSize: Int, key: RIndexKey): Int = {
    val hash = getHash(key)
    val innerPower = Integer.SIZE - Integer.numberOfLeadingZeros(dataSize)
    (hash >> (Integer.SIZE - rootPower - innerPower)) & ((1 << innerPower)-1)
  }

  def wrapData(options: RIndexOptions, data: Array[RIndexBucket]): RIndex =
    if(data.forall(isEmpty)) EmptyRIndex else new RIndexImpl(options, data)

  def isEmpty(r: RIndexBucket): Boolean = r.keys.length == 0
  def isEmpty(index: RIndex): Boolean = index eq EmptyRIndex

  def merge(aIndex: RIndex, bIndex: RIndex, mergeItems: (Seq[RIndexItem],Seq[RIndexItem])=>Seq[RIndexItem]): RIndex = (aIndex,bIndex) match {
    case (a,b) if isEmpty(a) => b
    case (a,b) if isEmpty(b) => a
    case (aI:RIndexImpl,bI:RIndexImpl) =>
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
      wrapData(options, Array.tabulate[RIndexBucket](aD.length){ i =>
        val aR: RIndexBucket = aD(i)
        val bR: RIndexBucket = bD(i)
        if(isEmpty(aR)) bR else if(isEmpty(bR)) aR else {
          val work = new Array[Pair](aR.keys.length+bR.keys.length)
          val aE: Array[Pair] = getViews(aR)
          val bE: Array[Pair] = getViews(bR)
          val mergedSize = ArrayMerger.merge[Pair](aE,bE,work,comparator,mergePairs,nonEmptyP)
          if(mergedSize==0) emptyBucket else {
            val data = util.Arrays.copyOf(work, mergedSize)
            val (ends,toPos) = makeInnerIndex(options,data)
            for (i <- data.indices){
              val pos = toPos(data(i))
              assert(i >= starts(ends,pos) && i < ends(pos))
            }
            toBucket(data, ends)
          }
        }
      })
  }

  def get(index: RIndex, key: RIndexKey): Seq[RIndexItem] = index match {
    case a if isEmpty(a) => Nil
    case aI: RIndexImpl =>
      val bucket: RIndexBucket = aI.data(keyToPosInRoot(aI.options.power,key))
      val iPos = keyToInnerPos(aI.options.power, bucket.keys.length, key)
      val start = bucket.hashPartToKeyRange.starts(iPos)
      val end = bucket.hashPartToKeyRange.ends(iPos)
      val sz = end - start
      if(sz < 1) Nil
      else if(sz == 1){
        if(compareKeys(key,bucket.keys(start)) == 0) getValueView(bucket,start)
        else Nil
      }
      else {
        val found: Int =
          util.Arrays.binarySearch[RIndexKey](bucket.keys, start, end, key, aI.options.keyComparator)
        if(found>=0) getValueView(bucket,found) else Nil
      }
  }

  def keyIterator(index: RIndex): Iterator[RIndexKey] = index match {
    case a if isEmpty(a) => Iterator.empty
    case aI: RIndexImpl => aI.data.iterator.flatMap(_.keys)
  }

  def toBucket(data: Array[Pair], ends: Array[Int]): RIndexBucket = {
    val hashPartToKeyRange = compressIndex(ends)
    val valueSizes = new Array[Int](data.length)
    for(i <- data.indices) valueSizes(i) = data(i)._2.size // check boxing
    val valueEnds = getEnds(valueSizes)
    val keyPosToValueRange = compressIndex(valueEnds)
    val keys = new Array[RIndexKey](data.length)
    val values = new Array[RIndexItem](valueEnds.last)
    for(i <- data.indices) {
      val (key,value) = data(i)
      keys(i) = key
      val dPos: Int = keyPosToValueRange.starts(i)
      val done = value.copyToArray(values,dPos)
      assert(value.nonEmpty && value.length == keyPosToValueRange.ends(i) - dPos && done == value.length)
    }
    new RIndexBucket(keys, hashPartToKeyRange, values, keyPosToValueRange)
  }

  def getValueView(bucket: RIndexBucket, pos: Int): Seq[RIndexItem] = {
    val start = bucket.keyPosToValueRange.starts(pos)
    val end = bucket.keyPosToValueRange.ends(pos)
    new RIndexSeq(bucket.values, start, end - start)
  }

  def getViews(bucket: RIndexBucket): Array[Pair] =
    Array.tabulate(bucket.keys.length)(i=>(bucket.keys(i), getValueView(bucket,i)))

  def eqBuckets(a: RIndex, b: RIndex, key: RIndexKey): Boolean = (a,b) match {
    case (a,b) if a eq b => true
    case (aI:RIndexImpl,bI:RIndexImpl) =>
      aI.data(keyToPosInRoot(aI.options.power,key)) eq bI.data(keyToPosInRoot(bI.options.power,key))
    case _ => false
  }


}

//arr.mapInPlace() arr.slice
// util.Arrays.copyOfRange(bucket.subData,from,to)


import scala.util.control.NonFatal
final class RIndexUtilDebug(inner: RIndexUtil) extends RIndexUtil {
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
  def build(power: Int, src: Iterator[Pair]): RIndex =
    wrap("build",inner.build(power, src))
  def eqBuckets(a: RIndex, b: RIndex, key: RIndexKey): Boolean =
    wrap("eqBuckets",inner.eqBuckets(a,b,key))
}