package ee.cone.c4assemble

import ee.cone.c4assemble.RIndexTypes._

import java.util
import java.util.Comparator
import scala.annotation.tailrec
import scala.collection.mutable

final class RIndexImpl(
  val data: Array[RIndexBucket],
  val nonEmptyParts: Long,
) extends RIndex {
  var keyCountCache: Int = -1
}

final class RIndexBucket(
  val rootPower: Int,
  val innerPower: Int,
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

abstract class RIndexSpread {
  def toPos(pair: RIndexPairImpl): Int
  def toDest(pos: Int): Array[RIndexPairImpl]
  def apply(src: Array[RIndexPairImpl], ends: Array[Int]): Array[Int] = {
    val starts = ends.clone()
    var i = 0
    while(i < src.length){
      val item = src(i)
      val pos = toPos(item)
      starts(pos) -= 1
      toDest(pos)(starts(pos)) = item
      i += 1
    }
    starts
  }
}

trait RKeyFoundHandler[@specialized(Boolean)T] {
  def handleEmpty(): T
  def handleNonEmptyKey(keyRHash: Int, bucket: RIndexBucket, found: Int): T
}

final class RIndexPairImpl(
  val creatorPos: Int, val rIndexKey: RIndexKey, val rIndexItem: RIndexItem, val rIndexKeyRHash: Int
) extends RIndexPair

final class RNonEmptyChecker extends RKeyFoundHandler[Boolean] {
  def handleEmpty(): Boolean = false
  def handleNonEmptyKey(keyRHash: Int, bucket: RIndexBucket, found: Int): Boolean = true
}

final class RIndexUtilImpl(
  arrayUtil: ArrayUtil,
  val emptyBucket: RIndexBucket = new RIndexBucket(
    0, 0, EmptyInnerIndex, Array.empty, EmptyInnerIndex, Array.empty
  ),
  maxPower: Int = 14,
  emptyKeys: Array[RIndexKey] = Array.empty,
  nonEmptyChecker: RKeyFoundHandler[Boolean] = new RNonEmptyChecker,
  noHashCodeCache: HashCodeCache = new HashCodeCache(0)
)(
  emptyBuckets: Array[RIndexBucket] = Array.fill(1 << maxPower)(emptyBucket)
) extends RIndexUtil {
  private val keyComparator = new Comparator[RIndexKey] {
    def compare(xK: RIndexKey, yK: RIndexKey): Int = compareKeys(xK, yK)
  }
  private val rawGetter = new ValueGetter

  private def getHash(s: RIndexKey): Int = Integer.reverseBytes(s.hashCode)

  private def compareByKeys(xP: RIndexPairImpl, yP: RIndexPairImpl): Int = {
    val xK = xP.rIndexKey
    val yK = yP.rIndexKey
    if(xK eq yK) 0 else compareKeysInner(xK, xP.rIndexKeyRHash, yK, yP.rIndexKeyRHash)
  }
  private def compareKeys(xK: RIndexKey, yK: RIndexKey): Int =
    if(xK eq yK) 0 else compareKeysInner(xK, getHash(xK), yK, getHash(yK))
  private def compareKeys(xK: RIndexKey, xH: Int, yK: RIndexKey): Int =
    if(xK eq yK) 0 else compareKeysInner(xK, xH, yK, getHash(yK))
  private def compareKeysInner(xK: RIndexKey, xH: Int, yK: RIndexKey, yH: Int): Int =
    if (xH < yH) -1 else if (xH > yH) 1 else (xK:Object) match { // what if All will have same hash as some String?
      case xS: String => (yK:Object) match {
        case yS: String => xS.compareTo(yS)
      }
    }

  private def rHashToPosInRoot(power: Int, hash: Int): Int = (hash >> (Integer.SIZE - power)) + (1 << (power - 1))

  def starts(ends: Array[Int], i: Int): Int = if(i==0) 0 else ends(i-1)

  def fillSizes(sizes: Array[Int], srcSize: Int, toPos: Int=>Int): Unit =
    for (i <- 0 until srcSize) sizes(toPos(i)) += 1

  //// indexing start
  private val indexPartCount = 32

  private final class RecalculationTaskImpl(val diffs: Array[RIndexImpl], val start: Int, val end: Int) extends RecalculationTask
  def recalculate(diffs: Array[RIndex]): Array[RecalculationTask] = {
    var nonEmptyParts = 0L
    val nonEmptyIndexesBuffer = new RIndexBuffer[RIndexImpl](new Array[RIndexImpl](diffs.length))
    var iPos = 0
    var size = -1
    while(iPos < diffs.length){
      val diff = diffs(iPos)
      nonEmptyParts |= getNonEmptyParts(diff)
      diff match {
        case a if isEmpty(a) => ()
        case aI: RIndexImpl =>
          nonEmptyIndexesBuffer.add(aI)
          if(size < 0) size = aI.data.length else assert(size == aI.data.length)
      }
      iPos += 1
    }
    val nonEmptyIndexes = nonEmptyIndexesBuffer.result()
    val res = new RIndexBuffer[RecalculationTask](new Array(indexPartCount))
    if(size > 0) {
      val partSize = getPartSize(size, indexPartCount)
      var partPos = 0
      while (partPos < indexPartCount) {
        val shifted = 1 << partPos
        val contains = (nonEmptyParts & shifted) != 0L
        if (contains) {
          val start = partSize * partPos
          val end = partSize * (partPos + 1)
          res.add(new RecalculationTaskImpl(nonEmptyIndexes, start, end))
        }
        partPos += 1
      }
    }
    res.result()
  }
  def execute(task: RecalculationTask, handle: RIndexKey=>Unit): Unit = {
    val taskImpl = task match {
      case t: RecalculationTaskImpl => t
    }
    val keySets = new Array[Array[RIndexKey]](taskImpl.diffs.length)
    val keySetBuffer = new RIndexBuffer[Array[RIndexKey]](keySets)
    val wasKeys = mutable.HashSet.empty[RIndexKey]
    var bPos = taskImpl.start
    while(bPos < taskImpl.end){
      keySetBuffer.end = 0
      var iPos = 0
      while(iPos < taskImpl.diffs.length){
        val keys = taskImpl.diffs(iPos).data(bPos).keys
        if(keys.length > 0) keySetBuffer.add(keys)
        iPos += 1
      }
      val needDedup = keySetBuffer.end > 1
      while(keySetBuffer.end > 0){
        keySetBuffer.end -= 1
        val keySet = keySets(keySetBuffer.end)
        var keyPos = 0
        while(keyPos < keySet.length){
          val key = keySet(keyPos)
          if(!needDedup || wasKeys.add(key)) handle(key)
          keyPos += 1
        }
      }
      if(needDedup) wasKeys.clear()
      bPos += 1
    }
  }

  private object BuildFlattenHandler extends FlattenHandler[Array[RIndexPair],RIndexPair,RIndexPairImpl] {
    def get(src: Array[RIndexPair]): Array[RIndexPair] = src
    def create(size: Int): Array[RIndexPairImpl] = new Array[RIndexPairImpl](size)
  }

  def buildIndex(prev: Array[RIndex], srcs: Array[Array[RIndexPair]], valueOperations: RIndexValueOperations): IndexingTask = {
    val src = arrayUtil.flatten[Array[RIndexPair],RIndexPair,RIndexPairImpl](srcs, BuildFlattenHandler)
    val power = 10
    /* dynamic power -- works, but complicates calc-task-creation
      val power: Int = Single.option(prev.collect{ case aI: RIndexImpl => getPowerStrict(aI.data.length) }.distinct)
-      .getOrElse(Math.max(10, Math.min(getPower(src.length)-7, maxPower)))
    */
    val size = 1 << power
    val sizes = new Array[Int](size)
    fillSizes(sizes, src.length, i=>rHashToPosInRoot(power, src(i).rIndexKeyRHash))
    val empty = Array.empty[RIndexPairImpl]
    val dest = new Array[Array[RIndexPairImpl]](size)
    for(i <- sizes.indices) dest(i) = if (sizes(i) == 0) empty else new Array(sizes(i))
    (new RIndexSpread {
      def toPos(pair: RIndexPairImpl): Int = rHashToPosInRoot(power, pair.rIndexKeyRHash)
      def toDest(pos: Int): Array[RIndexPairImpl] = dest(pos)
    })(src, sizes)
    val subTasks: Array[IndexingSubTaskImpl] = {
      val partSize = getPartSize(size, indexPartCount)
      val out = new RIndexBuffer(new Array[IndexingSubTaskImpl](indexPartCount))
      for(partPos <- 0 until indexPartCount){
        val start = partSize * partPos
        val end = partSize * (partPos+1)
        if(existsAboveZeroInRange(sizes, start, end))
          out.add(new IndexingSubTaskImpl(prev, dest, valueOperations, start, end, partPos))
      }
      out.result()
    }
    new IndexingTaskImpl(subTasks, prev)
  }

  @tailrec private def existsAboveZeroInRange(items: Array[Int], start: Int, end: Int): Boolean =
    start < end && (items(start) > 0 || existsAboveZeroInRange(items, start + 1, end))

  private final class IndexingTaskImpl(val subTasks: Seq[IndexingSubTask], val prev: Array[RIndex]) extends IndexingTask
  private final class IndexingSubTaskImpl(
    val prev: Array[RIndex], val pairsByBucket: Array[Array[RIndexPairImpl]], val valueOperations: RIndexValueOperations,
    val start: Int, val end: Int, val partPos: Int
  ) extends IndexingSubTask
  private def copyBuckets(index: RIndex, start: Int, sz: Int): Array[RIndexBucket] = {
    val res = new Array[RIndexBucket](sz)
    val src = index match {
      case a if isEmpty(a) => emptyBuckets
      case aI: RIndexImpl => aI.data
    }
    System.arraycopy(src, start, res, 0, sz)
    res
  }
  def execute(subTask: IndexingSubTask): IndexingResult = {
    val st = subTask match {
      case t: IndexingSubTaskImpl => t
    }
    val bucketsGroups = new Array[Array[RIndexBucket]](st.prev.length)
    for(i <- st.prev.indices) bucketsGroups(i) = copyBuckets(st.prev(i), st.start, st.end - st.start)
    var maxSize = 0
    for (buckets <- bucketsGroups) for (i <- buckets.indices)
      maxSize = Math.max(maxSize, st.pairsByBucket(st.start + i).length + buckets(i).values.length)
    val builder = new RIndexBucketBuilder(this, maxSize)()
    val hashCodeCache = new HashCodeCache(maxSize)
    val kvComparator: Comparator[RIndexPairImpl] = (aPair, bPair) => {
      val kRes = compareByKeys(aPair, bPair)
      if (kRes != 0) kRes else st.valueOperations.compare(aPair.rIndexItem, bPair.rIndexItem, hashCodeCache)
    }
    val rootPower = getPowerStrict(st.pairsByBucket.length)
    var changed = false
    for (pos <- st.start until st.end) {
      val pairs = st.pairsByBucket(pos)
      if(pairs.length > 0){
        val diffBucket = buildBucket(pairs, rootPower, builder, hashCodeCache, kvComparator, st.valueOperations)
        if(!isEmpty(diffBucket)) for(bGrPos <- bucketsGroups.indices){
          val buckets = bucketsGroups(bGrPos)
          val prevBucket = buckets(pos - st.start)
          val nextBucket = if(isEmpty(prevBucket)) diffBucket
            else mergeBuckets(prevBucket, diffBucket, rootPower, builder, hashCodeCache, st.valueOperations)
          buckets(pos - st.start) = nextBucket
          changed = true
        }
      }
    }
    if(!changed) new NoIndexingResult(st) else {
      val nonEmptyGroups = new Array[Boolean](bucketsGroups.length)
      var bGrPos = 0
      while(bGrPos < bucketsGroups.length){
        val bGr = bucketsGroups(bGrPos)
        var nonEmptyGroup = false
        var pos = 0
        while(pos < bGr.length && !nonEmptyGroup){
          if(bGr(pos).keys.length > 0) nonEmptyGroup = true
          pos += 1
        }
        nonEmptyGroups(bGrPos) = nonEmptyGroup
        bGrPos += 1
      }
      new IndexingResultImpl(st, bucketsGroups, st.start, st.partPos, nonEmptyGroups)
    }
  }
  private final class IndexingResultImpl(
    val subTask: IndexingSubTaskImpl, val bucketsGroups: Array[Array[RIndexBucket]], val start: Int,
    val partPos: Int, val nonEmptyGroups: Array[Boolean]
  ) extends IndexingResult
  private final class NoIndexingResult(val subTask: IndexingSubTaskImpl) extends IndexingResult
  private def buildBucket(
    pairs: Array[RIndexPairImpl], power: Int,
    builder: RIndexBucketBuilder, hashCodeCache: HashCodeCache, kvComparator: Comparator[RIndexPairImpl],
    valueOperations: RIndexValueOperations
  ): RIndexBucket = {
    builder.restart()
    val innerPower = getPower(pairs.length)
    if(pairs.length > 1) java.util.Arrays.sort(pairs, kvComparator)
    val positions = calcInnerPositions[RIndexPairImpl](power,innerPower,pairs,_.rIndexKeyRHash)
    val valueGrouping: RIndexBuildGroupBy = new RIndexBuildGroupBy {
      @tailrec def merge(value: RIndexItem, pos: Int, end: Int): RIndexItem =
        if(pos < end)
          merge(valueOperations.merge(value,pairs(pos).rIndexItem),pos+1,end)
        else value
      def compare(a: Int, b: Int): Int =
        valueOperations.compare(pairs(a).rIndexItem,pairs(b).rIndexItem,hashCodeCache)
      def forEachGroup(start: Int, end: Int): Unit = {
        val value = merge(pairs(start).rIndexItem, start+1, end)
        if(valueOperations.nonEmpty(value)) builder.addValue(value)
      }
    }
    val keyGrouping = new RIndexBuildGroupBy {
      def compare(a: Int, b: Int): Int =
        compareByKeys(pairs(a), pairs(b))
      def forEachGroup(start: Int, end: Int): Unit = {
        valueGrouping(start,end)
        builder.addKey(pairs(start).rIndexKey,positions(start))
      }
    }
    keyGrouping(0,pairs.length)
    builder.result(power, innerPower)
  }
  private def mergeBuckets(
    aBucket: RIndexBucket, bBucket: RIndexBucket,
    needRootPower: Int, builder: RIndexBucketBuilder, hashCodeCache: HashCodeCache,
    valueOperations: RIndexValueOperations
  ): RIndexBucket = {
    builder.restart()
    val aKeys = aBucket.keys
    val bKeys = bBucket.keys
    val needInnerPower = getPower(aKeys.length + bKeys.length)
    assert(needRootPower == aBucket.rootPower && needRootPower == bBucket.rootPower)
    val aPositions = restoreInnerPositions(aBucket, needInnerPower)
    val bPositions = restoreInnerPositions(bBucket, needInnerPower)
    val valueMerger = new BinaryMerge {
      def compare(ai: Int, bi: Int): Int =
        valueOperations.compare(aBucket.values(ai), bBucket.values(bi), hashCodeCache)
      def collision(ai: Int, bi: Int): Unit = {
        val value = valueOperations.merge(aBucket.values(ai), bBucket.values(bi))
        if (valueOperations.nonEmpty(value)) builder.addValue(value)
      }
      def fromA(a0: Int, a1: Int, bi: Int): Unit = builder.addValues(aBucket, a0, a1)
      def fromB(ai: Int, b0: Int, b1: Int): Unit = builder.addValues(bBucket, b0, b1)
    }
    @tailrec def add(bucket: RIndexBucket, positions: Array[Int], keysStart: Int, keysEnd: Int): Unit =
      if (keysStart < keysEnd) {
        builder.addValues(
          bucket,
          bucket.keyPosToValueRange.starts(keysStart),
          bucket.keyPosToValueRange.ends(keysStart)
        )
        builder.addKey(bucket.keys(keysStart), positions(keysStart))
        add(bucket, positions, keysStart + 1, keysEnd)
      }
    val keyMerger: BinaryMerge = new BinaryMerge {
      def compare(ai: Int, bi: Int): Int = {
        val r = Integer.compare(aPositions(ai), bPositions(bi))
        if (r != 0) r else compareKeys(aKeys(ai), bKeys(bi))
      }
      def collision(ai: Int, bi: Int): Unit = {
        valueMerger.merge0(
          aBucket.keyPosToValueRange.starts(ai),
          aBucket.keyPosToValueRange.ends(ai),
          bBucket.keyPosToValueRange.starts(bi),
          bBucket.keyPosToValueRange.ends(bi),
        )
        builder.addKey(aKeys(ai), aPositions(ai))
      }
      def fromA(a0: Int, a1: Int, bi: Int): Unit = add(aBucket, aPositions, a0, a1)
      def fromB(ai: Int, b0: Int, b1: Int): Unit = add(bBucket, bPositions, b0, b1)
    }
    keyMerger.merge0(0, aKeys.length, 0, bKeys.length)
    builder.result(needRootPower, needInnerPower)
  }
  def merge(task: IndexingTask, parts: Array[IndexingResult]): Seq[RIndex] = {
    val taskImpl = task match {
      case t: IndexingTaskImpl => t
    }
    assert(taskImpl.subTasks.size == parts.length)
    var partsEnd = 0
    val partSeq = new Array[IndexingResultImpl](parts.length)
    var i = 0
    while(i < parts.length){
      parts(i) match {
        case s: NoIndexingResult =>
          assert(s.subTask == taskImpl.subTasks(i))
        case s: IndexingResultImpl =>
          assert(s.subTask == taskImpl.subTasks(i))
          partSeq(partsEnd) = s
          partsEnd += 1
      }
      i += 1
    }
    if(partsEnd == 0) taskImpl.prev else {
      val res = new Array[RIndex](taskImpl.prev.length)
      var grPos = 0
      while(grPos < taskImpl.prev.length){
        val prevIndex = taskImpl.prev(grPos)
        var nonEmptyParts = getNonEmptyParts(prevIndex)
        var partPos = 0
        while(partPos < partsEnd){
          val part = partSeq(partPos)
          val shifted = 1 << part.partPos
          nonEmptyParts = if(part.nonEmptyGroups(grPos)) nonEmptyParts | shifted else nonEmptyParts & ~shifted
          partPos += 1
        }
        res(grPos) = if (nonEmptyParts == 0L) EmptyRIndex else {
          val data = copyBuckets(prevIndex, 0, partSeq(0).subTask.pairsByBucket.length)
          while(partPos > 0){
            partPos -= 1
            val part = partSeq(partPos)
            val src = part.bucketsGroups(grPos)
            System.arraycopy(src, 0, data, part.start, src.length)
          }
          new RIndexImpl(data, nonEmptyParts)
        }
        grPos += 1
      }
      res
    }
  }

  private def getNonEmptyParts(index: RIndex) = index match {
    case a if isEmpty(a) => 0L
    case aI: RIndexImpl => aI.nonEmptyParts
  }

  //// indexing end
  def getPower(sz: Int): Int = Integer.SIZE - Integer.numberOfLeadingZeros(sz) // (sz < 1<<power) will hold -- 1024 for 1023 and 2048 for 1024
  private def getPowerStrict(sz: Int): Int = {
    assert(Integer.bitCount(sz)==1) // check if sz is power of 2
    Integer.numberOfTrailingZeros(sz)
  }

  private def keyRHashToInnerPos(rootPower: Int, innerPower: Int, rHash: Int): Int =
    (rHash >> (Integer.SIZE - rootPower - innerPower)) & ((1 << innerPower)-1)

  def isEmpty(r: RIndexBucket): Boolean = r.keys.length == 0
  def isEmpty(index: RIndex): Boolean = index eq EmptyRIndex

  private def restoreInnerPositions(bucket: RIndexBucket, needInnerPower: Int): Array[Int] = {
    if(bucket.innerPower != needInnerPower)
      calcInnerPositions(bucket.rootPower, needInnerPower, bucket.keys, getHash)
    else {
      val res = new Array[Int](bucket.keys.length)
      for(i <- 0 until (1 << bucket.innerPower)){
        val start = bucket.hashPartToKeyRange.starts(i)
        val end = bucket.hashPartToKeyRange.ends(i)
        if(start < end) java.util.Arrays.fill(res,start,end,i)
      }
      //assert(java.util.Arrays.equals(res,calcInnerPositions(needPowers, bucket.keys, identity[RIndexKey])))
      res
    }
  }

  private def calcInnerPositions[T](rootPower: Int, innerPower: Int, data: Array[T], toKeyRHash: T=>Int): Array[Int] = {
    val res = new Array[Int](data.length)
    for(i <- data.indices) res(i) = keyRHashToInnerPos(rootPower, innerPower, toKeyRHash(data(i)))
    res
  }



  /*
  def subIndexOptimalCount(index: RIndex): Int = index match {
    case aI if isEmpty(aI) => 1
    case aI: RIndexImpl =>
      //32
      if(aI.valueCount > 1024) 64 else 32
      //Math.min(8, Math.max(1 << (getPower(aI.valueCount)-5), aI.data.length))
  }

  def subIndexKeys(index: RIndex, partPos: Int, partCount: Int): Array[RIndexKey] = index match {
    case aI if isEmpty(aI) => emptyKeys
    case aI: RIndexImpl =>
      val size = getPartSize(aI.data.length, partCount)
      new RFlatten[RIndexKey,RIndexKey] {
        def get(pos: Int): Array[RIndexKey] = aI.data(pos).keys
        def create(size: Int): Array[RIndexKey] = new Array[RIndexKey](size)
      }.apply(partPos*size, (partPos+1)*size)
  }*/

  private def getPartSize(itemCount: Int, partCount: Int): Int = {
    assert(itemCount % partCount == 0)
    itemCount / partCount
  }

  def create(creatorPos: Int, key: RIndexKey, value: RIndexItem): RIndexPair =
    new RIndexPairImpl(creatorPos, key, value, getHash(key))

  private def findBucket(aI: RIndexImpl, keyRHash: Int) =
    aI.data(rHashToPosInRoot(getPowerStrict(aI.data.length), keyRHash))

  private def findKey[@specialized(Boolean)T](index: RIndex, key: RIndexKey, handler: RKeyFoundHandler[T]): T = index match {
    case a if isEmpty(a) => handler.handleEmpty()
    case aI: RIndexImpl =>
      val keyRHash = getHash(key)
      val bucket = findBucket(aI, keyRHash)
      if (bucket.keys.length == 0) handler.handleEmpty() else {
        val iPos = keyRHashToInnerPos(bucket.rootPower, bucket.innerPower, keyRHash)
        val start = bucket.hashPartToKeyRange.starts(iPos)
        val end = bucket.hashPartToKeyRange.ends(iPos)
        val sz = end - start
        if (sz < 1) handler.handleEmpty()
        else if (sz == 1) {
          if (compareKeys(key, keyRHash, bucket.keys(start)) != 0) handler.handleEmpty()
          else handler.handleNonEmptyKey(keyRHash, bucket, start)
        }
        else {
          val found = util.Arrays.binarySearch[RIndexKey](bucket.keys, start, end, key, keyComparator)
          if (found < 0) handler.handleEmpty() else handler.handleNonEmptyKey(keyRHash, bucket, found)
        }
      }
  }

  def get(index: RIndex, key: RIndexKey): Seq[RIndexItem] = findKey(index, key, rawGetter)
  def nonEmpty(index: RIndex, key: RIndexKey): Boolean = findKey(index, key, nonEmptyChecker)
  private final class ValueGetter extends RKeyFoundHandler[Seq[RIndexItem]] {
    def handleEmpty(): Seq[RIndexItem] = Nil
    def handleNonEmptyKey(keyRHash: Int, bucket: RIndexBucket, pos: Int): Seq[RIndexItem] = {
      val start = bucket.keyPosToValueRange.starts(pos)
      val end = bucket.keyPosToValueRange.ends(pos)
      new RIndexSeq(bucket.values, start, end - start)
    }
  }

  def keyIterator(index: RIndex): Iterator[RIndexKey] = index match {
    case a if isEmpty(a) => Iterator.empty
    case aI: RIndexImpl => aI.data.iterator.flatMap(_.keys)
  }

  private object KeyCountSumHandler extends SumHandler[RIndexBucket] {
    def get(src: RIndexBucket): Long = src.keys.length
  }

  def keyCount(index: RIndex): Int = index match {
    case a if isEmpty(a) => 0
    case aI: RIndexImpl =>
      if(aI.keyCountCache < 0) aI.keyCountCache = java.lang.Math.toIntExact(arrayUtil.sum(aI.data, KeyCountSumHandler))
      aI.keyCountCache
  }

  private object ValueCountSumHandler extends SumHandler[RIndexBucket] {
    def get(src: RIndexBucket): Long = src.values.length
  }

  def valueCount(index: RIndex): Int = index match {
    case a if isEmpty(a) => 0
    case aI: RIndexImpl => java.lang.Math.toIntExact(arrayUtil.sum(aI.data, ValueCountSumHandler))
  }

  def eqBuckets(a: RIndex, b: RIndex, key: RIndexKey): Boolean = (a,b) match {
    case (a,b) if a eq b => true
    case (aI:RIndexImpl,bI:RIndexImpl) =>
      val keyRHash = getHash(key)
      findBucket(aI,keyRHash) eq findBucket(bI,keyRHash)
    case _ => false
  }

  def changed(values: Seq[RIndexItem], diff: Seq[RIndexItem], valueOperations: RIndexValueOperations): Array[RIndexItem] = {
    val builder = new RIndexBuffer[RIndexItem](new Array(diff.length))
    val bm = new BinaryMerge {
      def compare(ai: Int, bi: Int): Int =
        valueOperations.compare(values(ai),diff(bi), noHashCodeCache)
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
        valueOperations.compare(values(ai),diff(bi), noHashCodeCache)
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

  @tailrec private def findOther(start: Int, pos: Int, end: Int): Int =
    if(pos < end && compare(start,pos)==0)
      findOther(start, pos+1, end) else pos

  @tailrec final def apply(start: Int, end: Int): Unit =
    if(start < end){
      val other = findOther(start, start+1, end)
      forEachGroup(start, other)
      apply(other, end)
    }
}

final class RIndexBucketBuilder(
  util: RIndexUtilImpl,
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

  private def lastKeyToV: Int = if(destKeys.end > 0) destKeyToV(destKeys.end-1) else 0
  def addKey(key: RIndexKey, hashPart: Int): Unit = if(destValues.end > lastKeyToV) {
    destKeyToV(destKeys.end) = destValues.end
    destKeyToHash(destKeys.end) = hashPart
    destKeys.add(key)
  }

  def restart(): Unit = {
    destKeys.end = 0
    destValues.end = 0
  }

  def result(rootPower: Int, innerPower: Int): RIndexBucket =
    if(destKeys.end==0) util.emptyBucket else new RIndexBucket(
      rootPower, innerPower, makeInnerIndex(innerPower), destKeys.result(),
      compressIndex(destKeyToV, destKeys.end), destValues.result(),
    )

  private def sizesToEnds(indexLength: Int): Unit =
    for (i <- 0 until indexLength)
      destHashToK(i) = util.starts(destHashToK,i) + destHashToK(i)

  private def makeInnerIndex(innerPower: Int): InnerIndex = {
    val indexLength = 1 << innerPower
    java.util.Arrays.fill(destHashToK,0,indexLength,0)
    util.fillSizes(destHashToK, destKeys.end, i=>destKeyToHash(i))
    sizesToEnds(indexLength)
    assert(destHashToK(indexLength-1)==destKeys.end)
    compressIndex(destHashToK,indexLength)
  }

  private def compressIndex(ends: Array[Int], length: Int): InnerIndex = {
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