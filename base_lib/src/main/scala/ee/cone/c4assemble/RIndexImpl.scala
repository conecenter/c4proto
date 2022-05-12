package ee.cone.c4assemble

import ee.cone.c4assemble.RIndexTypes._

import java.util
import java.util.Comparator
import scala.annotation.tailrec

final class RIndexImpl(
  val options: RIndexOptions,
  val data: Array[RIndexBucket],
) extends RIndex

final class RIndexBucket(
  val hashPartToKeyRange: InnerIndex,
  val keys: Array[RIndexKey],
  val keyPosToValueRange: InnerIndex,
  val values: Array[RIndexItem],
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

final class RIndexSeq(val values: Array[RIndexItem], val start: Int, val length: Int) extends  IndexedSeq[RIndexItem] {
  def apply(i: Int): RIndexItem = values(start+i)
  override def copyToArray[B >: RIndexItem](dest: Array[B], destStart: Int): Int = {
    System.arraycopy(values, start, dest, destStart, length)
    length
  }
}

final class RIndexOptions(val power: Int, val keyComparator: Comparator[RIndexKey])

abstract class RIndexSpread {
  def toPos(pair: RIndexPair): Int
  def toDest(pos: Int): Array[RIndexPair]
  def apply(src: Seq[RIndexPair], ends: Array[Int]): Array[Int] = {
    val starts = ends.clone()
    for (item <- src){
      val pos = toPos(item)
      starts(pos) -= 1
      toDest(pos)(starts(pos)) = item
    }
    starts
  }
}

final class RIndexUtilImpl(
  val emptyBucket: RIndexBucket = new RIndexBucket(
    EmptyInnerIndex, Array.empty, EmptyInnerIndex, Array.empty
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

  def starts(ends: Array[Int], i: Int): Int = if(i==0) 0 else ends(i-1)

  def fillSizes(sizes: Array[Int], srcSize: Int, toPos: Int=>Int): Unit =
    for (i <- 0 until srcSize) sizes(toPos(i)) += 1

  def build(power: Int, src: Array[RIndexPair], valueOperations: RIndexValueOperations): RIndex = {
    val keyComparator: Comparator[RIndexKey] = compareKeys(_,_)
    val options = new RIndexOptions(power,keyComparator)
    val size = 1 << options.power
    val sizes = new Array[Int](size)
    fillSizes(sizes, src.length, i=>keyToPosInRoot(options.power, src(i).rIndexKey))
    val empty = Array.empty[RIndexPair]
    val dest: Array[Array[RIndexPair]] =
      sizes.map(s=>if(s==0) empty else new Array(s))
    (new RIndexSpread {
      def toPos(pair: RIndexPair): Int =
        keyToPosInRoot(options.power, pair.rIndexKey)
      def toDest(pos: Int): Array[RIndexPair] = dest(pos)
    })(src, sizes)
    val kvComparator: Comparator[RIndexPair] = (aPair,bPair)=>{
      val kRes = compareKeys(aPair.rIndexKey,bPair.rIndexKey)
      if(kRes != 0) kRes else valueOperations.compareInPairs(aPair,bPair)
    }
    val builder = new RIndexBucketBuilder(this, options, sizes.max)()
    wrapData(options, dest.map{ pairs =>
      builder.restart()
      if(pairs.length > 0){
        java.util.Arrays.sort(pairs, kvComparator)
        val valueGrouping: RIndexBuildGroupBy = new RIndexBuildGroupBy {
          @tailrec def merge(value: RIndexItem, pos: Int, end: Int): RIndexItem =
            if(pos < end)
              merge(valueOperations.merge(value,pairs(pos).rIndexItem),pos+1,end)
            else value
          def compare(a: Int, b: Int): Int =
            valueOperations.compareInPairs(pairs(a),pairs(b))
          def forEachGroup(start: Int, end: Int): Unit = {
            val value = merge(pairs(start).rIndexItem, start+1, end)
            if(valueOperations.nonEmpty(value)) builder.addValue(value)
          }
        }
        val keyGrouping = new RIndexBuildGroupBy {
          def compare(a: Int, b: Int): Int =
            compareKeys(pairs(a).rIndexKey, pairs(b).rIndexKey)
          def forEachGroup(start: Int, end: Int): Unit = {
            valueGrouping(start,end)
            builder.addKey(pairs(start).rIndexKey)
          }
        }
        keyGrouping(0,pairs.length)
      }
      builder.result()
    })
  }

  def getPower(sz: Int): Int = Integer.SIZE - Integer.numberOfLeadingZeros(sz)

  def keyToInnerPos(rootPower: Int, dataSize: Int, key: RIndexKey): Int = {
    val hash = getHash(key)
    val innerPower = getPower(dataSize)
    (hash >> (Integer.SIZE - rootPower - innerPower)) & ((1 << innerPower)-1)
  }

  def wrapData(options: RIndexOptions, data: Array[RIndexBucket]): RIndex =
    if(data.forall(isEmpty)) EmptyRIndex else new RIndexImpl(options, data)

  def isEmpty(r: RIndexBucket): Boolean = r.keys.length == 0
  def isEmpty(index: RIndex): Boolean = index eq EmptyRIndex

  def merge(
    aIndex: RIndex, bIndex: RIndex, valueOperations: RIndexValueOperations,
  ): RIndex = (aIndex,bIndex) match {
    case (a,b) if isEmpty(a) => b
    case (a,b) if isEmpty(b) => a
    case (aI:RIndexImpl,bI:RIndexImpl) =>
      val options = aI.options
      val aD = aI.data
      val bD = bI.data
      assert(aD.length == bD.length, "merge length")
      val buckets: Array[RIndexBucket] = aD.clone()
      val maxSize = buckets.indices.iterator.map{ i =>
        val aSz = aD(i).values.length
        val bSz = bD(i).values.length
        if(aSz > 0 && bSz > 0) aSz+bSz else 0
      }.max
      val builder = new RIndexBucketBuilder(this, options, maxSize)()
      for(i <- buckets.indices){
        val aBucket = aD(i)
        val bBucket = bD(i)
        if(isEmpty(bBucket)) ()
        else if(isEmpty(aBucket)) buckets(i) = bBucket
        else {
          builder.restart()
          val aKeys = aBucket.keys
          val bKeys = bBucket.keys

          val valueMerger = new BinaryMerge {
            def compare(ai: Int, bi: Int): Int =
              valueOperations.compare(aBucket.values(ai), bBucket.values(bi))
            def collision(ai: Int, bi: Int): Unit = {
              val value = valueOperations.merge(aBucket.values(ai), bBucket.values(bi))
              if(valueOperations.nonEmpty(value)) builder.addValue(value)
            }
            def fromA(a0: Int, a1: Int, bi: Int): Unit = builder.addValues(aBucket, a0, a1)
            def fromB(ai: Int, b0: Int, b1: Int): Unit = builder.addValues(bBucket, b0, b1)
          }
          @tailrec def add(bucket: RIndexBucket, keysStart: Int, keysEnd: Int): Unit =
            if(keysStart < keysEnd){
              builder.addValues(
                bucket,
                bucket.keyPosToValueRange.starts(keysStart),
                bucket.keyPosToValueRange.ends(keysStart)
              )
              builder.addKey(bucket.keys(keysStart))
              add(bucket, keysStart+1, keysEnd)
            }
          val keyMerger: BinaryMerge = new BinaryMerge {
            def compare(ai: Int, bi: Int): Int =
              compareKeys(aKeys(ai), bKeys(bi))
            def collision(ai: Int, bi: Int): Unit = {
              valueMerger.merge0(
                aBucket.keyPosToValueRange.starts(ai),
                aBucket.keyPosToValueRange.ends(ai),
                bBucket.keyPosToValueRange.starts(bi),
                bBucket.keyPosToValueRange.ends(bi),
              )
              builder.addKey(aKeys(ai))
            }
            def fromA(a0: Int, a1: Int, bi: Int): Unit = add(aBucket, a0, a1)
            def fromB(ai: Int, b0: Int, b1: Int): Unit = add(bBucket, b0, b1)
          }
          keyMerger.merge0(0,aKeys.length,0,bKeys.length)
          buckets(i) = builder.result()
        }
      }
      wrapData(options, buckets)
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

  def getValueView(bucket: RIndexBucket, pos: Int): Seq[RIndexItem] = {
    val start = bucket.keyPosToValueRange.starts(pos)
    val end = bucket.keyPosToValueRange.ends(pos)
    new RIndexSeq(bucket.values, start, end - start)
  }

  def eqBuckets(a: RIndex, b: RIndex, key: RIndexKey): Boolean = (a,b) match {
    case (a,b) if a eq b => true
    case (aI:RIndexImpl,bI:RIndexImpl) =>
      aI.data(keyToPosInRoot(aI.options.power,key)) eq bI.data(keyToPosInRoot(bI.options.power,key))
    case _ => false
  }

  def changed(values: Seq[RIndexItem], diff: Seq[RIndexItem], valueOperations: RIndexValueOperations): Array[RIndexItem] = {
    val builder = new RIndexBuffer[RIndexItem](new Array(diff.length))
    (new BinaryMerge {
      def compare(ai: Int, bi: Int): Int =
        valueOperations.compare(values(ai),diff(bi))
      def collision(ai: Int, bi: Int): Unit = builder.add(values(ai))
      def fromA(a0: Int, a1: Int, bi: Int): Unit = ()
      def fromB(ai: Int, b0: Int, b1: Int): Unit = ()
    }).merge0(0,values.length,0,diff.length)
    builder.result()
  }
  def unchanged(values: Seq[RIndexItem], diff: Seq[RIndexItem], valueOperations: RIndexValueOperations): Array[RIndexItem] = {
    val builder = new RIndexBuffer[RIndexItem](new Array(values.length))
    (new BinaryMerge {
      def compare(ai: Int, bi: Int): Int =
        valueOperations.compare(values(ai),diff(bi))
      def collision(ai: Int, bi: Int): Unit = ()
      def fromA(a0: Int, a1: Int, bi: Int): Unit =
        for(i <- a0 until a1) builder.add(values(i))
      def fromB(ai: Int, b0: Int, b1: Int): Unit = ()
    }).merge0(0,values.length,0,diff.length)
    builder.result()
  }
}

abstract class RIndexBuildGroupBy {
  def compare(a: Int, b: Int): Int
  def forEachGroup(start: Int, end: Int): Unit

  @tailrec final def findOther(start: Int, pos: Int, end: Int): Int =
    if(pos < end && compare(start,pos)==0)
      findOther(start, pos+1, end) else pos

  @tailrec final def apply(start: Int, end: Int): Unit =
    if(start < end){
      val other = findOther(start, start+1, end)
      forEachGroup(start, other)
      apply(other, end)
    }
}

final class RIndexBuffer[T<:Object](values: Array[T]){
  var end: Int = 0
  def add(src: Array[T], srcStart: Int, sz: Int): Unit = {
    System.arraycopy(src, srcStart, values, end, sz)
    end += sz
  }
  def add(value: T): Unit = {
    values(end) = value
    end += 1
  }
  def result(): Array[T] = java.util.Arrays.copyOf[T](values, end)
}

final class RIndexBucketBuilder(
  util: RIndexUtilImpl,
  options: RIndexOptions,
  val maxSize: Int,
)(
  destHashToK: Array[Int] = new Array(1 << util.getPower(maxSize)),
  destKeys: RIndexBuffer[RIndexKey] = new RIndexBuffer(new Array(maxSize)),
  destKeyToV: Array[Int] = new Array(maxSize),
  destValues: RIndexBuffer[RIndexItem] = new RIndexBuffer(new Array(maxSize)),
){
  def addValue(value: RIndexItem): Unit = destValues.add(value)
  def addValues(bucket: RIndexBucket, start: Int, end: Int): Unit =
    destValues.add(bucket.values, start, end-start)

  def lastKeyToV: Int = if(destKeys.end > 0) destKeyToV(destKeys.end-1) else 0
  def addKey(key: RIndexKey): Unit = if(destValues.end > lastKeyToV) {
    destKeyToV(destKeys.end) = destValues.end
    destKeys.add(key)
  }

  def restart(): Unit = {
    destKeys.end = 0
    destValues.end = 0
  }

  def result(): RIndexBucket = if(destKeys.end==0) util.emptyBucket else {
    val keys = destKeys.result()
    new RIndexBucket(
      makeInnerIndex(keys), keys,
      compressIndex(destKeyToV, destKeys.end), destValues.result(),
    )
  }

  def sizesToEnds(indexLength: Int): Unit =
    for (i <- 0 until indexLength)
      destHashToK(i) = util.starts(destHashToK,i) + destHashToK(i)

  def makeInnerIndex(keys: Array[RIndexKey]): InnerIndex = {
    val indexLength = 1 << util.getPower(keys.length)
    java.util.Arrays.fill(destHashToK,0,indexLength,0)
    util.fillSizes(destHashToK, keys.length, i=>util.keyToInnerPos(options.power,keys.length,keys(i)))
    sizesToEnds(indexLength)
    assert(destHashToK(indexLength-1)==keys.length)
    compressIndex(destHashToK,indexLength)
  }

  def compressIndex(ends: Array[Int], length: Int): InnerIndex = {
    val last = ends(length-1)
    if(last == length) OneToOneInnerIndex // because there's no empty
    else if(last <= Byte.MaxValue){
      val res = new Array[Byte](length)
      for(i <- 0 until length) res(i) = ends(i).toByte // .map boxes
      new SmallInnerIndex(res)
    }
    else if(last <= Char.MaxValue){
      val res = new Array[Char](length)
      for(i <- 0 until length) res(i) = ends(i).toChar
      new DefInnerIndex(res)
    }
    else new BigInnerIndex(java.util.Arrays.copyOf(ends,length))
  }
}

// b prefer small

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
  def merge(a: RIndex, b: RIndex, valueOperations: RIndexValueOperations): RIndex =
    wrap("merge",inner.merge(a,b,valueOperations))
  def keyIterator(index: RIndex): Iterator[RIndexKey] =
    wrap("iterator",inner.keyIterator(index))
  def build(power: Int, src: Array[RIndexPair], valueOperations: RIndexValueOperations): RIndex =
    wrap("build",inner.build(power, src, valueOperations))
  def eqBuckets(a: RIndex, b: RIndex, key: RIndexKey): Boolean =
    wrap("eqBuckets",inner.eqBuckets(a,b,key))
  def changed(values: Seq[RIndexItem], diff: Seq[RIndexItem], valueOperations: RIndexValueOperations): Array[RIndexItem] =
    wrap("changed",inner.changed(values,diff,valueOperations))
  def unchanged(values: Seq[RIndexItem], diff: Seq[RIndexItem], valueOperations: RIndexValueOperations): Array[RIndexItem] =
    wrap("unchanged",inner.unchanged(values,diff,valueOperations))
}