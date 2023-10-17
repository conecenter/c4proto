package ee.cone.c4assemble

import ee.cone.c4assemble.RIndexTypes._

import java.util
import java.util.Comparator
import scala.annotation.tailrec
import scala.collection.ArrayOps
import scala.collection.immutable.ArraySeq

final class RIndexImpl(
  val options: RIndexOptions,
  val data: Array[RIndexBucket],
  val keyCount: Int,
  val valueCount: Int,
) extends RIndex

final class RIndexBucket(
  val powers: RIndexPowers,
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

final class RIndexPowers(val rootPower: Int, val innerPower: Int)

final class RIndexUtilImpl(
  val emptyBucket: RIndexBucket = new RIndexBucket(
    new RIndexPowers(0,0), EmptyInnerIndex, Array.empty, EmptyInnerIndex, Array.empty
  ),
  val maxPower: Int = 12
)(
  val emptyBuckets: Array[RIndexBucket] = Array.fill(1 << maxPower)(emptyBucket)
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

  def buildIndex(power: Int, src: Array[RIndexPair], valueOperations: RIndexValueOperations): IndexingTask = {
    //val started = System.nanoTime()
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
    val handler = new BuildIndexingHandler(power, valueOperations, options, dest)
    makeIndexingTask(handler, sizes, Math.max(1000, src.length / 32), options)
  }

  private class BuildIndexingHandler(
    val power: Int, val valueOperations: RIndexValueOperations, val options: RIndexOptions,
    val pairsByBucket: Array[Array[RIndexPair]]
  ) extends IndexingHandler {
    val kvComparator: Comparator[RIndexPair] = (aPair, bPair) => {
      val kRes = compareKeys(aPair.rIndexKey,bPair.rIndexKey)
      if(kRes != 0) kRes else valueOperations.compare(aPair.rIndexItem,bPair.rIndexItem)
    }
    def handle(pos: Int, builder: RIndexBucketBuilder): RIndexBucket = {
      val pairs = pairsByBucket(pos)
      if(pairs.length == 0) emptyBucket else {
        builder.restart()
        val powers = new RIndexPowers(power,getPower(pairs.length))
        if(pairs.length > 1) java.util.Arrays.sort(pairs, kvComparator)
        val positions = calcInnerPositions[RIndexPair](powers,pairs,_.rIndexKey)
        val valueGrouping: RIndexBuildGroupBy = new RIndexBuildGroupBy {
          @tailrec def merge(value: RIndexItem, pos: Int, end: Int): RIndexItem =
            if(pos < end)
              merge(valueOperations.merge(value,pairs(pos).rIndexItem),pos+1,end)
            else value
          def compare(a: Int, b: Int): Int =
            valueOperations.compare(pairs(a).rIndexItem,pairs(b).rIndexItem)
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
            builder.addKey(pairs(start).rIndexKey,positions(start))
          }
        }
        keyGrouping(0,pairs.length)
        builder.result(powers)
      }
    }
  }

  def getPower(sz: Int): Int = Integer.SIZE - Integer.numberOfLeadingZeros(sz)

  def keyToInnerPos(powers: RIndexPowers, key: RIndexKey): Int = {
    val hash = getHash(key)
    (hash >> (Integer.SIZE - powers.rootPower - powers.innerPower)) & ((1 << powers.innerPower)-1)
  }

  ////

  private def makeIndexingTask(
    handler: IndexingHandler, itemCountsByBucket: Array[Int], minItemsPerSubTask: Int, options: RIndexOptions
  ): IndexingTask = {
    @tailrec def makeSubTasks(start: Int, end: Int, items: Int, maxItemsPerBucket: Int, res: List[IndexingSubTask]): Seq[IndexingSubTask] = {
      if (end == itemCountsByBucket.length) new IndexingSubTaskImpl(handler, start, end, maxItemsPerBucket) :: res
      else if (items > minItemsPerSubTask)
        makeSubTasks(end, end, 0, 0, new IndexingSubTaskImpl(handler, start, end, maxItemsPerBucket) :: res)
      else {
        val itemCount = itemCountsByBucket(end)
        makeSubTasks(start, end + 1, items + itemCount, Math.max(maxItemsPerBucket, itemCount), res)
      }
    }
    new IndexingTaskImpl(makeSubTasks(0, 0, 0, 0, Nil))
  }

  def execute(subTask: IndexingSubTask): IndexingResult = {
    val st = subTask match { case t: IndexingSubTaskImpl => t }
    val handler = st.handler
    val buckets = new Array[RIndexBucket](st.end - st.start)
    val builder = new RIndexBucketBuilder(this, handler.options, st.maxSize)()
    var keyCount = 0
    var valueCount = 0
    for(pos <- st.start until st.end){
      val bucket = handler.handle(pos, builder)
      buckets(pos - st.start) = bucket
      keyCount += bucket.keys.length
      valueCount += bucket.values.length
    }
    new IndexingResultImpl(st, buckets, st.start, keyCount, valueCount)
  }

  def merge(task: IndexingTask, parts: Seq[IndexingResult]): RIndex = {
    val taskImpl = task match {
      case t: IndexingTaskImpl => t
    }
    val partSeq = parts.asInstanceOf[Seq[IndexingResultImpl]]
    assert(taskImpl.subTasks.toSet == partSeq.map(_.subTask).toSet)
    var keyCount = 0
    var valueCount = 0
    for(part <- partSeq) {
        keyCount += part.keyCount
        valueCount += part.valueCount
    }
    if (keyCount <= 0) EmptyRIndex else {
      val data: Array[RIndexBucket] = emptyBuckets.clone()
      for(part <- partSeq) System.arraycopy(part.data, 0, data, part.start, part.data.length)
      val options = taskImpl.subTasks.head match { case t: IndexingSubTaskImpl =>  t.handler.options }
      new RIndexImpl(options, data, keyCount, valueCount)
    }
  }

  private trait IndexingHandler {
    def handle(pos: Int, builder: RIndexBucketBuilder): RIndexBucket
    def options: RIndexOptions
  }
  private class IndexingTaskImpl(val subTasks: Seq[IndexingSubTask]) extends IndexingTask
  private class IndexingSubTaskImpl(
    val handler: IndexingHandler, val start: Int, val end: Int, val maxSize: Int
  ) extends IndexingSubTask
  private class IndexingResultImpl(
    val subTask: IndexingSubTaskImpl, val data: Array[RIndexBucket], val start: Int,
    val keyCount: Int, val valueCount: Int
  ) extends IndexingResult

  ////

  def isEmpty(r: RIndexBucket): Boolean = r.keys.length == 0
  def isEmpty(index: RIndex): Boolean = index eq EmptyRIndex

  def restoreInnerPositions(bucket: RIndexBucket, needPowers: RIndexPowers): Array[Int] = {
    if(bucket.powers.innerPower != needPowers.innerPower)
      calcInnerPositions(needPowers, bucket.keys, identity[RIndexKey])
    else {
      val res = new Array[Int](bucket.keys.length)
      for(i <- 0 until (1 << bucket.powers.innerPower)){
        val start = bucket.hashPartToKeyRange.starts(i)
        val end = bucket.hashPartToKeyRange.ends(i)
        if(start < end) java.util.Arrays.fill(res,start,end,i)
      }
      //assert(java.util.Arrays.equals(res,calcInnerPositions(needPowers, bucket.keys, identity[RIndexKey])))
      res
    }
  }

  def calcInnerPositions[T](powers: RIndexPowers, data: Array[T], toKey: T=>RIndexKey): Array[Int] = {
    val res = new Array[Int](data.length)
    for(i <- data.indices) res(i) = keyToInnerPos(powers, toKey(data(i)))
    res
  }

  def mergeIndex(aIndex: RIndex, bIndex: RIndex, valueOperations: RIndexValueOperations): IndexingTask = {
    val Seq(aI,bI) = Seq(aIndex,bIndex).filterNot(isEmpty).map{ case i: RIndexImpl => i }
    val aData = aI.data
    val bData = bI.data
    assert(aData.length==bData.length)
    val itemCountsByBucket = new Array[Int](aData.length)
    var i = 0
    while(i < aData.length){
      itemCountsByBucket(i) = aData(i).values.length + bData(i).values.length
      i += 1
    }
    val itemCount = aI.valueCount + bI.valueCount
    val options = aI.options
    val handler = new MergeIndexingHandler(options, valueOperations, aI, bI)
    makeIndexingTask(handler, itemCountsByBucket, Math.max(1000, itemCount / 32), options)
  }

  private class MergeIndexingHandler(
    val options: RIndexOptions, val valueOperations: RIndexValueOperations,
    val aIndex: RIndexImpl, val bIndex: RIndexImpl
  ) extends IndexingHandler {
    def handle(pos: Int, builder: RIndexBucketBuilder): RIndexBucket = {
        val aBucket: RIndexBucket = aIndex.data(pos)
        val bBucket: RIndexBucket = bIndex.data(pos)
        if(isEmpty(bBucket)) aBucket else if(isEmpty(aBucket)) bBucket else {
          builder.restart()
          val aKeys = aBucket.keys
          val bKeys = bBucket.keys
          val needPowers = new RIndexPowers(options.power,getPower(aKeys.length+bKeys.length))
          val aPositions = restoreInnerPositions(aBucket,needPowers)
          val bPositions = restoreInnerPositions(bBucket,needPowers)
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
          @tailrec def add(bucket: RIndexBucket, positions: Array[Int], keysStart: Int, keysEnd: Int): Unit =
            if(keysStart < keysEnd){
              builder.addValues(
                bucket,
                bucket.keyPosToValueRange.starts(keysStart),
                bucket.keyPosToValueRange.ends(keysStart)
              )
              builder.addKey(bucket.keys(keysStart),positions(keysStart))
              add(bucket, positions, keysStart+1, keysEnd)
            }
          val keyMerger: BinaryMerge = new BinaryMerge {
            def compare(ai: Int, bi: Int): Int = {
              val r = Integer.compare(aPositions(ai),bPositions(bi))
              if(r != 0) r else compareKeys(aKeys(ai), bKeys(bi))
            }
            def collision(ai: Int, bi: Int): Unit = {
              valueMerger.merge0(
                aBucket.keyPosToValueRange.starts(ai),
                aBucket.keyPosToValueRange.ends(ai),
                bBucket.keyPosToValueRange.starts(bi),
                bBucket.keyPosToValueRange.ends(bi),
              )
              builder.addKey(aKeys(ai),aPositions(ai))
            }
            def fromA(a0: Int, a1: Int, bi: Int): Unit = add(aBucket, aPositions, a0, a1)
            def fromB(ai: Int, b0: Int, b1: Int): Unit = add(bBucket, bPositions, b0, b1)
          }
          keyMerger.merge0(0,aKeys.length,0,bKeys.length)
          builder.result(needPowers)
        }
    }
  }

  private val emptyKeys = Array.empty[RIndexKey]
  def subIndexKeys(index: RIndex, partPos: Int, partCount: Int): Array[RIndexKey] = index match {
    case aI if isEmpty(aI) => emptyKeys
    case aI: RIndexImpl =>
      val size = aI.data.length / partCount
      assert(aI.data.length % partCount == 0)
      val start = partPos*size
      val end = (partPos+1)*size
      var kPos = 0
      var oPos = start
      while(oPos < end){
        val keys =  aI.data(oPos).keys
        kPos += keys.length
        oPos += 1
      }
      val res = new Array[RIndexKey](kPos)
      while(oPos > start){
        oPos -= 1
        val keys =  aI.data(oPos).keys
        kPos -= keys.length
        if(keys.length > 0) System.arraycopy(keys, 0, res, kPos, keys.length)
      }
      assert(kPos == 0)
      res
  }

  def findKey(options: RIndexOptions, bucket: RIndexBucket, key: RIndexKey): Int = {
    val iPos = keyToInnerPos(bucket.powers, key)
    val start = bucket.hashPartToKeyRange.starts(iPos)
    val end = bucket.hashPartToKeyRange.ends(iPos)
    val sz = end - start
    if(sz < 1) -1
    else if(sz == 1){
      if(compareKeys(key,bucket.keys(start)) == 0) start else -1
    }
    else util.Arrays.binarySearch[RIndexKey](bucket.keys, start, end, key, options.keyComparator)
  }

  def findBucket(aI: RIndexImpl, key: RIndexKey): RIndexBucket =
    aI.data(keyToPosInRoot(aI.options.power,key))

  def get(index: RIndex, key: RIndexKey): Seq[RIndexItem] = index match {
    case a if isEmpty(a) => Nil
    case aI: RIndexImpl =>
      val bucket = findBucket(aI,key)
      val found = findKey(aI.options, bucket, key)
      if(found>=0) getValueView(bucket,found) else Nil
  }

  def nonEmpty(index: RIndex, key: RIndexKey): Boolean = index match {
    case a if isEmpty(a) => false
    case aI: RIndexImpl => findKey(aI.options, findBucket(aI,key), key) >= 0
  }

  def keyIterator(index: RIndex): Iterator[RIndexKey] = index match {
    case a if isEmpty(a) => Iterator.empty
    case aI: RIndexImpl => aI.data.iterator.flatMap(_.keys)
  }

  def keyCount(index: RIndex): Int = index match {
    case a if isEmpty(a) => 0
    case aI: RIndexImpl => aI.keyCount
  }

  def valueCount(index: RIndex): Int = index match {
    case a if isEmpty(a) => 0
    case aI: RIndexImpl => aI.valueCount
  }

  def getValueView(bucket: RIndexBucket, pos: Int): Seq[RIndexItem] = {
    val start = bucket.keyPosToValueRange.starts(pos)
    val end = bucket.keyPosToValueRange.ends(pos)
    new RIndexSeq(bucket.values, start, end - start)
  }

  def eqBuckets(a: RIndex, b: RIndex, key: RIndexKey): Boolean = (a,b) match {
    case (a,b) if a eq b => true
    case (aI:RIndexImpl,bI:RIndexImpl) => findBucket(aI,key) eq findBucket(bI,key)
    case _ => false
  }

  def changed(values: Seq[RIndexItem], diff: Seq[RIndexItem], valueOperations: RIndexValueOperations): Array[RIndexItem] = {
    val builder = new RIndexBuffer[RIndexItem](new Array(diff.length))
    val bm = new BinaryMerge {
      def compare(ai: Int, bi: Int): Int =
        valueOperations.compare(values(ai),diff(bi))
      def collision(ai: Int, bi: Int): Unit = builder.add(values(ai))
      def fromA(a0: Int, a1: Int, bi: Int): Unit = ()
      def fromB(ai: Int, b0: Int, b1: Int): Unit = ()
    }
    bm.merge0(0,values.length,0,diff.length)
    builder.result()
  }
  def unchanged(values: Seq[RIndexItem], diff: Seq[RIndexItem], valueOperations: RIndexValueOperations): Array[RIndexItem] = {
    val builder = new RIndexBuffer[RIndexItem](new Array(values.length))
    val bm = new BinaryMerge {
      def compare(ai: Int, bi: Int): Int =
        valueOperations.compare(values(ai),diff(bi))
      def collision(ai: Int, bi: Int): Unit = ()
      def fromA(a0: Int, a1: Int, bi: Int): Unit =
        for(i <- a0 until a1) builder.add(values(i))
      def fromB(ai: Int, b0: Int, b1: Int): Unit = ()
    }
    bm.merge0(0,values.length,0,diff.length)
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
/*
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
}*/

final class RIndexBucketBuilder(
  util: RIndexUtilImpl,
  options: RIndexOptions,
  val maxSize: Int,
)(
  destHashToK: Array[Int] = new Array(1 << util.getPower(maxSize)),
  destKeyToHash: Array[Int] = new Array(maxSize),
  destKeys: RIndexBuffer[RIndexKey] = new RIndexBuffer[RIndexKey](new Array(maxSize)),
  destKeyToV: Array[Int] = new Array(maxSize),
  destValues: RIndexBuffer[RIndexItem] = new RIndexBuffer[RIndexItem](new Array(maxSize)),
){
  def addValue(value: RIndexItem): Unit = destValues.add(value)
  def addValues(bucket: RIndexBucket, start: Int, end: Int): Unit =
    destValues.add(bucket.values, start, end-start)

  def lastKeyToV: Int = if(destKeys.end > 0) destKeyToV(destKeys.end-1) else 0
  def addKey(key: RIndexKey, hashPart: Int): Unit = if(destValues.end > lastKeyToV) {
    destKeyToV(destKeys.end) = destValues.end
    destKeyToHash(destKeys.end) = hashPart
    destKeys.add(key)
  }

  def restart(): Unit = {
    destKeys.end = 0
    destValues.end = 0
  }

  def result(powers: RIndexPowers): RIndexBucket =
    if(destKeys.end==0) util.emptyBucket else new RIndexBucket(
      powers, makeInnerIndex(powers), destKeys.result(),
      compressIndex(destKeyToV, destKeys.end), destValues.result(),
    )

  def sizesToEnds(indexLength: Int): Unit =
    for (i <- 0 until indexLength)
      destHashToK(i) = util.starts(destHashToK,i) + destHashToK(i)

  def makeInnerIndex(powers: RIndexPowers): InnerIndex = {
    val indexLength = 1 << powers.innerPower
    java.util.Arrays.fill(destHashToK,0,indexLength,0)
    util.fillSizes(destHashToK, destKeys.end, i=>destKeyToHash(i))
    sizesToEnds(indexLength)
    assert(destHashToK(indexLength-1)==destKeys.end)
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

/*
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
  def nonEmpty(index: RIndex, key: RIndexKey): Boolean =
    wrap("nonEmpty",inner.nonEmpty(index,key))
  def isEmpty(index: RIndex): Boolean =
    wrap("isEmpty",inner.isEmpty(index))
  def merge(indexes: Seq[RIndex], valueOperations: RIndexValueOperations): RIndex =
    wrap("merge",inner.merge(indexes,valueOperations))
  def subIndex(index: RIndex, partPos: Int, partCount: Int): RIndex =
    wrap("subIndex",inner.subIndex(index, partPos, partCount))
  def keyIterator(index: RIndex): Iterator[RIndexKey] =
    wrap("iterator",inner.keyIterator(index))
  def keyCount(index: RIndex): Int =
    wrap("keyCount",inner.keyCount(index))
  def valueCount(index: RIndex): Int =
    wrap("valueCount",inner.valueCount(index))
  def build(power: Int, src: Array[RIndexPair], valueOperations: RIndexValueOperations): RIndex =
    wrap("build",inner.build(power, src, valueOperations))
  def eqBuckets(a: RIndex, b: RIndex, key: RIndexKey): Boolean =
    wrap("eqBuckets",inner.eqBuckets(a,b,key))
  def changed(values: Seq[RIndexItem], diff: Seq[RIndexItem], valueOperations: RIndexValueOperations): Array[RIndexItem] =
    wrap("changed",inner.changed(values,diff,valueOperations))
  def unchanged(values: Seq[RIndexItem], diff: Seq[RIndexItem], valueOperations: RIndexValueOperations): Array[RIndexItem] =
    wrap("unchanged",inner.unchanged(values,diff,valueOperations))
}
*/