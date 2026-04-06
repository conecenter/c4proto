package ee.cone.c4gate_devel

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.{DisableDefObserver, GetOffset, ListConfig, PreHashed, RichContext, WorldCheckHandler}
import ee.cone.c4assemble.{IndexUtil, ReadModel, ReadModelUtil}
import ee.cone.c4di.{c4, provide}

import scala.annotation.tailrec
import scala.collection.mutable

trait Eq {
  def hash(x: AnyRef): Int
  def same(a: AnyRef, b: AnyRef): Boolean
}

object ObjectEq extends Eq {
  def hash(x: AnyRef): Int = x.hashCode()
  def same(a: AnyRef, b: AnyRef): Boolean = a.equals(b)
}

object IdentityEq extends Eq {
  def hash(x: AnyRef): Int = System.identityHashCode(x)
  def same(a: AnyRef, b: AnyRef): Boolean = a eq b
}

//object CharArrayEq extends Eq {
//  def hash(x: AnyRef): Int = java.util.Arrays.hashCode(x.asInstanceOf[Array[Char]])
//  def same(a: AnyRef, b: AnyRef): Boolean =
//    java.util.Arrays.equals(a.asInstanceOf[Array[Char]], b.asInstanceOf[Array[Char]])
//}

final class DedupGatherer(eq: Eq, power: Int) {
  private val size = 1 << power
  private val table = new Array[AnyRef](size)
  private val mask = size - 1

  private def add(a: Array[AnyRef], pos: Int, v: AnyRef): Boolean = {
    a(pos) = v
    true
  }

  @tailrec private def findOrAdd(a: Array[AnyRef], pos: Int, end: Int, v: AnyRef): Boolean = a(pos) match {
    case w: Array[AnyRef] => findOrAdd(w, 0, w.length, v)
    case null => add(a, pos, v)
    case w if eq.same(w, v) => false
    case _ if pos + 1 < end => findOrAdd(a, pos + 1, end, v)
    case w => add(a, pos, Array[AnyRef](w, v, null, null, null))
  }

  def findOrAdd(ref: AnyRef): Boolean = {
    val h = eq.hash(ref)
    val ix = (h ^ (h >>> 16)) & mask
    findOrAdd(table, ix, ix + 1, ref)
  }
}

@c4("DedupReportApp") final class DedupReporter(
  getOffset: GetOffset, readModelUtil: ReadModelUtil, indexUtil: IndexUtil, config: ListConfig,
) extends WorldCheckHandler with LazyLogging {
  @provide def disableDefObserver: Seq[DisableDefObserver] = Seq(new DisableDefObserver)
  def handle(context: RichContext): Unit =
    if(config.get("C4WORLD_CHECK_DEDUP") contains getOffset.of(context)) reportFindAssSeq(context.assembled)

  private def report0(assembled: ReadModel): Unit = {
    val idGatherer = new DedupGatherer(IdentityEq, 29)
    val eqGatherer = new DedupGatherer(ObjectEq, 29)

    class Counter(var ids: Long=0L, var eqs: Long=0L) {
      def inc(isEq: Boolean): Unit = if(isEq) eqs += 1 else ids += 1
    }
    class Counters() {
      val all = new Counter()
      val counters: mutable.Map[String,mutable.Map[String,Counter]] = mutable.Map()
      def inc(value: Object, isEq: Boolean): Unit = {
        all.inc(isEq)
        val n = value match {
          case _: Some[_] => "some"
          case _: List[_] => "list"
          case v: Product if v.productArity == 1 => "prod1"
          case _: Product => "prodM"
          case _ => "etc"
        }
        val k = value match {
          case v: Some[_] => v.value.getClass.getName
          case v: ::[_] => v.head.getClass.getName
          case v: Product => v.productPrefix
          case v => v.getClass.getName
        }
        counters.getOrElseUpdate(n, mutable.Map()).getOrElseUpdate(k, new Counter()).inc(isEq)
      }
    }
    val counters = new Counters()
    def chk(el: AnyRef): Unit = if(idGatherer.findOrAdd(el)){
      counters.inc(el, false)
      if(eqGatherer.findOrAdd(el)){
        counters.inc(el, true)
        el match {
          case l: Seq[Object@unchecked] => for(e <- l) chk(e)
          case l: Set[Object@unchecked] => for(e <- l) chk(e)
          case l: Map[_,Object@unchecked] => for(e <- l.values) chk(e)
          case p: PreHashed[Object@unchecked] => chk(p.value)
          case p: Product => for(i <- 0 until p.productArity) chk(p.productElement(i).asInstanceOf[Object])
          case _ => ()
        }
      }
    }

    val indexes = readModelUtil.toMap(assembled).toSeq
    indexes.zipWithIndex.foreach{ case ((wKey, index), i) =>
      logger.info(s"progress ${i+1}/${indexes.size} id ${counters.all.ids} eq ${counters.all.eqs} wk $wKey")
      for { pk <- indexUtil.keyIterator(index); pr <- indexUtil.getValues(index, pk, "") } chk(pr.asInstanceOf[Object])
    }
    counters.counters.toSeq.sortBy(_._1).foreach { case (tp, tCounter) =>
      tCounter.toSeq.sortBy(_._2.ids).takeRight(200).foreach { case (nm, count) =>
        logger.info(s"$tp id ${count.ids} eq ${count.eqs} cl $nm")
      }
    }
    logger.info(s"all id ${counters.all.ids} eq ${counters.all.eqs}")
  }

  private def reportFindDups(assembled: ReadModel): Unit = {
    val idGatherer = new DedupGatherer(IdentityEq, 29)
    val eqGatherer = new DedupGatherer(ObjectEq, 29)

    class Counter(var value: Long=0L)
    val counters = mutable.Map[String, mutable.Map[String, mutable.Map[String, Counter]]]()
    def chk(parentType: String, parentField: String, el: AnyRef): Unit = if(idGatherer.findOrAdd(el)){
      if(eqGatherer.findOrAdd(el)){
        //
        el match {
          case l: Seq[Object@unchecked] => for(e <- l) chk(parentType, parentField, e)
          case l: Set[Object@unchecked] => for(e <- l) chk(parentType, parentField, e)
          case l: Map[_,Object@unchecked] => for(e <- l.values) chk(parentType, parentField, e)
          case l: Option[_] => for(e <- l) chk(parentType, parentField, e.asInstanceOf[Object])
          case p: PreHashed[Object@unchecked] => chk(parentType, parentField, p.value)
          case p: Product =>
            for(i <- 0 until p.productArity)
              chk(p.productPrefix, p.productElementName(i), p.productElement(i).asInstanceOf[Object])
          case _ => ()
        }
      } else {
        counters.getOrElseUpdate(parentType, mutable.Map()).getOrElseUpdate(parentField, mutable.Map()).getOrElseUpdate(el.getClass.getName, new Counter).value += (el match {
          case v: Iterable[_] => v.size case _ => 1
        })
      }
    }
    val indexes = readModelUtil.toMap(assembled).toSeq
    indexes.zipWithIndex.foreach{ case ((wKey, index), i) =>
      logger.info(s"progress ${i+1}/${indexes.size} wk $wKey")
      for(pk <- indexUtil.keyIterator(index); pr <- indexUtil.getValues(index, pk, ""))
        chk("", "", pr.asInstanceOf[Object])
    }
    for {
      (parentType, v0) <- counters.toSeq.sortBy(_._1)
      (parentField, v1) <- v0.toSeq.sortBy(_._1)
      (className, counter) <- v1.toSeq.sortBy(_._1)
    } logger.info(s"found\t${counter.value}\t$parentType\t$parentField\t$className")
  }

  private def reportFindListAltSeq(assembled: ReadModel): Unit = {
    val idGatherer = new DedupGatherer(IdentityEq, 29)
    val eqGatherer = new DedupGatherer(ObjectEq, 29)

    class Counter(var value: Long=0L, var list: Long=0L, var vector: Long=0L, var seq: Long=0L)
    val total = new Counter
    val counters = mutable.Map[String, mutable.Map[String, Counter]]()
    class Other(var value: Long=0L)
    val others = mutable.Map[String, Other]()

    def align(v: Int): Int = if(v % 16 == 0) v else v + 16 - v % 16

    def chk(parentType: String, parentField: String, el: AnyRef): Unit = if(idGatherer.findOrAdd(el)){
      if(eqGatherer.findOrAdd(el)){
        el match {
          case l: List[Object@unchecked] =>
            val sz = l.size
            val counter = counters.getOrElseUpdate(parentType, mutable.Map()).getOrElseUpdate(parentField, new Counter)
            total.value += 1
            counter.value += 1
            if(sz > 0) {
              total.list += sz * 16
              counter.list += sz * 16
              counter.vector += 16 + align(12 + sz * 4)
              counter.seq += align(8 + sz * 4)
            }
            for(e <- l) chk(parentType, parentField, e)
          case l: Seq[Object@unchecked] => for(e <- l) chk(parentType, parentField, e)
          case l: Set[Object@unchecked] => for(e <- l) chk(parentType, parentField, e)
          case l: Map[_,Object@unchecked] => for(e <- l.values) chk(parentType, parentField, e)
          case l: Option[_] => for(e <- l) chk(parentType, parentField, e.asInstanceOf[Object])
          case p: PreHashed[Object@unchecked] => chk(parentType, parentField, p.value)
          case p: Product =>
            for(i <- 0 until p.productArity)
              chk(p.productPrefix, p.productElementName(i), p.productElement(i).asInstanceOf[Object])
          case o => others.getOrElseUpdate(o.getClass.getName, new Other()).value += 1
        }
      }
    }
    val indexes = readModelUtil.toMap(assembled).toSeq
    indexes.zipWithIndex.foreach{ case ((wKey, index), i) =>
      logger.info(s"progress ${i+1}/${indexes.size} lc ${total.value} wk $wKey")
      for(pk <- indexUtil.keyIterator(index); pr <- indexUtil.getValues(index, pk, ""))
        chk("", "", pr.asInstanceOf[Object])
    }
    for {
      (parentType, v0) <- counters.toSeq.sortBy(_._1)
      (parentField, counter) <- v0.toSeq.sortBy(_._1)
    } logger.info(s"found\t${counter.list}\t${counter.vector}\t${counter.seq}\t$parentType\t$parentField")
    for((ok,ov) <- others.toSeq.sortBy(_._1))
      logger.info(s"other $ok ${ov.value}")
    logger.info(s"list count ${total.value} total-size ${total.list}")
  }

  def reportFindAssSeq(assembled: ReadModel): Unit = {
    val idGatherer = new DedupGatherer(IdentityEq, 29)
    val found = mutable.Set[(String,String)]()

    def chk(parentType: String, parentField: String, el: AnyRef): Unit = if(idGatherer.findOrAdd(el)) el match {
      case l: Seq[Object@unchecked] =>
        if(l.getClass.getName contains "AssSeq") found += (parentType-> parentField)
        for(e <- l) chk(parentType, parentField, e)
      case l: Set[Object@unchecked] => for(e <- l) chk(parentType, parentField, e)
      case l: Map[_,Object@unchecked] => for(e <- l.values) chk(parentType, parentField, e)
      case l: Option[_] => for(e <- l) chk(parentType, parentField, e.asInstanceOf[Object])
      case p: PreHashed[Object@unchecked] => chk(parentType, parentField, p.value)
      case p: Product =>
        for(i <- 0 until p.productArity)
          chk(p.productPrefix, p.productElementName(i), p.productElement(i).asInstanceOf[Object])
      case _ => ()
    }

    val indexes = readModelUtil.toMap(assembled).toSeq
    indexes.zipWithIndex.foreach{ case ((wKey, index), i) =>
      logger.info(s"progress ${i+1}/${indexes.size} wk $wKey")
      for(pk <- indexUtil.keyIterator(index); pr <- indexUtil.getValues(index, pk, ""))
        chk("", "", pr.asInstanceOf[Object])
    }
    for (el <- found.toSeq.sorted) logger.info(s"found $el")
  }

}


/*
var idCount = 0
var idElCount = 0
var eqCount = 0
var eqElCount = 0
var bucketRefCount = 0
var nonEmptyBucketRefCount = 0
//val counters = mutable.Map[Seq[Char],Long]()
readModelUtil.toMap(assembled).values.foreach{
  case aI: RIndexImpl =>
    for(bucket <- aI.data; ii <- Seq(bucket.hashPartToKeyRange, bucket.keyPosToValueRange)) ii match {
      case index: DefInnerIndex =>

        if(idGatherer.findOrAdd(index)){
          idCount += 1
          if(eqGatherer.findOrAdd(index)){
            eqCount += 1
          }
          /*else {
            val seq = ArraySeq.unsafeWrapArray(arr)
            counters(seq) = counters.getOrElse(seq, 0L) + arr.length.toLong
          }*/
        }
      case index: SmallInnerIndex =>
        if(idGatherer.findOrAdd(index)){
          idCount += 1
          idElCount += index.size
          if(eqGatherer.findOrAdd(index)){
            eqCount += 1
            eqElCount += index.size
          }
        }
      case _ => ()
    }
    for(bucket <- aI.data){
      bucketRefCount += 1
      if(bucket.keys.length > 0) nonEmptyBucketRefCount += 1
    }

  case _ => ()
}
logger.info(s"SmallInnerIndex ${idCount} ${idElCount} ${eqCount} ${eqElCount}")
//logger.info(s"bucketRefCount-s $bucketRefCount $nonEmptyBucketRefCount")

logger.info(s"${counters.values.toSeq.sorted}")
counters.toSeq.sortBy(_._2).takeRight(20).foreach{ case (seq, count) =>
  if(count > 25) logger.info(s"$count ${seq.map(_.toInt)}")
}*/

/*
val valRefCount = mutable.Map[String,Long]()
val valIdCount = mutable.Map[String,Long]()
val valEqCount = mutable.Map[String,Long]()
def inc(counter: mutable.Map[String,Long], value: Object): Unit = {
  val k = value.getClass.getName
  counter(k) = counter.getOrElse(k, 0L) + 1
  counter("ALL") = counter.getOrElse("ALL", 0L) + 1
}
readModelUtil.toMap(assembled).values.foreach{ index =>
  for {
    pk <- indexUtil.keyIterator(index)
    pr <- indexUtil.getValues(index, pk, "")
  } {
    val value: Object = pr.asInstanceOf[Object]
    inc(valRefCount, value)
    if(idGatherer.findOrAdd(value)){
      inc(valIdCount, value)
      if(eqGatherer.findOrAdd(value)){
        inc(valEqCount, value)
      }
    }
  }
}
valIdCount.toSeq.sortBy(_._2).takeRight(200).foreach { case (nm, count) =>
  logger.info(s"$count ${valEqCount(nm)} ${valRefCount(nm)} $nm")
}
*/