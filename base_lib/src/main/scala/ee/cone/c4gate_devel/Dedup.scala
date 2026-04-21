package ee.cone.c4gate_devel

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.{ApproximateIntern, CheckedMap, GetByPK, GetOffset, ListConfig, PreHashed, RichContext, WorldCheckHandler}
import ee.cone.c4assemble.{IndexUtil, NonSingleCount, RIndex, ReadModel, ReadModelUtil, ToPrimaryKey}
import ee.cone.c4di.c4

import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets.UTF_8
import java.lang.management.ManagementFactory
import java.nio.file.Files
import scala.annotation.tailrec
import scala.collection.mutable

///

trait EnvWorldCheckHandler {
  def name: String
  def handle(context: RichContext): Unit
}

@c4("DevelReportApp") final class EnvDispatchWorldCheckHandler(
  config: ListConfig, handlers: List[EnvWorldCheckHandler]
) extends WorldCheckHandler {
  private val handlersByName = CheckedMap(handlers.map(h=>h.name->h))
  private val activeHandlers = for(v <- config.get("C4WORLD_CHECKS"); nm <- v.split(",")) yield handlersByName(nm)
  def handle(context: RichContext): Unit = for(h <- activeHandlers) h.handle(context)
}

///

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

///

@c4("DevelReportApp") final class SleepEnvWorldCheckHandler extends EnvWorldCheckHandler with LazyLogging {
  def name: String = "sleep"
  def handle(context: RichContext): Unit = {
    logger.info("GOING_TO_SLEEP")
    Thread.sleep(Long.MaxValue)
  }
}
import scala.jdk.CollectionConverters.CollectionHasAsScala
@c4("DevelReportApp") final class HistogramEnvWorldCheckHandler(approximateIntern: ApproximateIntern) extends EnvWorldCheckHandler with LazyLogging {
  def name: String = "histogram"
  def handle(context: RichContext): Unit = {
    val rt = ManagementFactory.getRuntimeMXBean
    logger.info(s"uptime ${rt.getUptime}ms")
    logger.info(s"ApproximateInterner ${approximateIntern.count}/${approximateIntern.size}")
    //
    val pid = ManagementFactory.getRuntimeMXBean.getPid
    val tmp = Files.createTempFile("class_histogram_",".log")
    val pb = new ProcessBuilder("jcmd",s"$pid","GC.class_histogram")
      .redirectInput(Redirect.INHERIT).redirectError(Redirect.INHERIT).redirectOutput(tmp.toFile)
    if(pb.start().waitFor() != 0) throw new Exception
    for(line <- Files.readAllLines(tmp).asScala if !line.head.isDigit) logger.info(line)
  }
}

@c4("DevelReportApp") final class WorldCheckTraverser(
  readModelUtil: ReadModelUtil, indexUtil: IndexUtil
) extends LazyLogging {
  def foreachIndex(context: RichContext, cb: RIndex=>Unit): Unit = {
    val indexes = readModelUtil.toMap(context.assembled).toSeq
    indexes.zipWithIndex.foreach{ case ((wKey, index), i) =>
      logger.info(s"progress ${i+1}/${indexes.size} $wKey")
      cb(index)
    }
  }
  class Counter(var value: Long=0L)
  def traverse[C](
    context: RichContext,
    rtCounter: C,
    visitRef: (C,Object)=>C,
    visitField: (C,String,String)=>C,
  ): Unit = {
    val idGatherer = new DedupGatherer(IdentityEq, 29)
    val others = mutable.Map[String, Counter]()
    def handleValue(pCounter: C, value: Object): Unit = if((value ne null) && idGatherer.findOrAdd(value)){
      val counter = visitRef(pCounter, value)
      value match {
        case l: Seq[Object@unchecked] => for(e <- l) handleValue(counter, e)
        case l: Set[Object@unchecked] => for(e <- l) handleValue(counter, e)
        case l: Map[_,Object@unchecked] => for(e <- l.values) handleValue(counter, e)
        case l: Option[Object@unchecked] => for(e <- l) handleValue(counter, e)
        case p: PreHashed[Object@unchecked] => handleValue(counter, p.value)
        case pr: Product =>
          val cl = pr.getClass
          for(field <- cl.getDeclaredFields if !field.getType.isPrimitive){
            field.setAccessible(true)
            handleValue(visitField(counter, pr.productPrefix, field.getName), field.get(pr))
          }
        case _: BigDecimal => ()
        case _: java.lang.Long => ()
        case _: String => ()
        case o => others.getOrElseUpdate(o.getClass.getName, new Counter()).value += 1
      }
    }
    foreachIndex(context, index => {
      for { pk <- indexUtil.keyIterator(index); pr <- indexUtil.getValues(index, pk, "") }
        handleValue(rtCounter,pr.asInstanceOf[Object])
    })
    for((count,clName) <- others.toSeq.map{ case (ok,ov) => (ov.value,ok) }.sorted if count > 1) logger.info(s"other-leaf $count $clName")
  }
}

@c4("DevelReportApp") final class AssSeqEnvWorldCheckHandler(
  worldCheckTraverser: WorldCheckTraverser,
) extends EnvWorldCheckHandler with LazyLogging {
  def name: String = "optseq"
  val (ass, fields, lists, cons, options, nums) = (0, 1, 2, 3, 4, 5)
  private val hints = Seq("ass", "fields", "lists", "cons", "options", "nums")
  private type Counter = Array[Long]
  private def makeCounter() = new Array[Long](hints.length)
  private def toStr(c: Counter) =
    (for(i <- Seq(ass,fields,lists,cons,options,nums) if c(i) > 0) yield s"${hints(i)} ${c(i)}").mkString(" ")
  def handle(context: RichContext): Unit = {
    val total = makeCounter()
    def inc(c: Counter, k: Int): Unit = {
      total(k) += 1
      c(k) += 1
    }
    val rtCounter: Counter = makeCounter()
    val counters = mutable.Map[String, mutable.Map[String, Counter]]()
    def visitRef(counter: Counter, value: Object): Counter = {
      value match {
        case l: List[_] =>
          inc(counter, lists)
          for(_ <- l) inc(counter, cons)
        case l: Seq[Object@unchecked] if l.getClass.getName contains "AssSeq" => inc(counter, ass)
        case _: Option[Object@unchecked] => inc(counter, options)
        case _: BigDecimal => inc(counter, nums)
        case _: java.lang.Long => inc(counter, nums)
        case _ => ()
      }
      counter
    }
    def visitField(counter: Counter, typeName: String, fieldName: String): Counter = {
      val cCounter = counters.getOrElseUpdate(typeName, mutable.Map()).getOrElseUpdate(fieldName, makeCounter())
      inc(cCounter, fields)
      cCounter
    }
    worldCheckTraverser.traverse(context, rtCounter, visitRef, visitField)
    //
    val found = for{
      (parentType, v0) <- counters.toSeq; (parentField, c) <- v0
      optim <- Seq(c(options)+c(cons)+c(nums)) if optim > 0
    } yield ((c(ass), optim), s"found\t${toStr(c)}\tat $parentType $parentField")
    for(line <- found.sortBy(_._1).map(_._2)) logger.info(line)
    logger.info(s"total\t${toStr(total)}")
  }
}

@c4("DevelReportApp") final class DedupEnvWorldCheckHandler(
  worldCheckTraverser: WorldCheckTraverser,
) extends EnvWorldCheckHandler with LazyLogging {
  def name: String = "dedup"
  type State = (String,String)
  type CountKey = (String,String,String,String)
  def handle(context: RichContext): Unit = {
    val eqGatherer = new DedupGatherer(ObjectEq, 29)
    val dupCounts = mutable.Map[CountKey, worldCheckTraverser.Counter]()
    def inc(k: CountKey): Unit = dupCounts.getOrElseUpdate(k, new worldCheckTraverser.Counter()).value += 1
    def visitRef(state: State, value: Object): State = {
      if(!eqGatherer.findOrAdd(value)) {
        val (typeName, fieldName) = state
        inc((typeName, fieldName, value.getClass.getName, ""))
        value match {
          case Some(v) => inc((typeName, fieldName, v.getClass.getName, "some"))
          case l: List[_] => for(v <- l) inc((typeName, fieldName, v.getClass.getName, "cons"))
          case _ => ()
        }
      }
      state
    }
    def visitField(state: State, typeName: String, fieldName: String): State = (typeName, fieldName)
    val rtState = ("","")
    worldCheckTraverser.traverse(context, rtState, visitRef, visitField)
    dupCounts.groupMap{
      case ((typeName, fieldName, clName, kind), count) => s"$kind/$clName"
    }{
      case ((typeName, fieldName, clName, kind), count) => (count.value, s"$typeName.$fieldName")
    }.transform{
      case (_,l) => (l.map(_._1).sum, l.toSeq.sortBy(_._1).takeRight(32))
    }.toSeq.sortBy{
      case (kcl, (count, l)) => count
    }.foreach{
      case (kcl, (count, l)) =>
        logger.info(s":$count $kcl")
        for((c,tf) <- l) logger.info(s"\t$c $tf")
    }
    //for((count,clName) <- dupCounts.toSeq.map{ case (ok,ov) => (ov.value,ok) }.sorted if count > 10000)
    //  logger.info(s"dups $count $clName")
  }
}

// only used to estimate gains for possible changes
@c4("DevelReportApp") final class BucketEnvWorldCheckHandler(
  worldCheckTraverser: WorldCheckTraverser, readModelUtil: ReadModelUtil
) extends EnvWorldCheckHandler with LazyLogging {
  def name: String = "bucket"
  def handle(context: RichContext): Unit = {
    val idGatherer = new DedupGatherer(IdentityEq, 29)
    val counts = mutable.Map[String, worldCheckTraverser.Counter]()
    def inc(k: String, v: Long): Unit = counts.getOrElseUpdate(k, new worldCheckTraverser.Counter()).value += v
    def align(v: Long): Long = if(v % 16 == 0) v else v + 16 - v % 16
    def len2bytes(v: Long): Long = align(12L + 4L*v)
    def incMb(hint: String, arr: Array[_]): Unit = {
      inc(s"$hint-count", 1)
      inc(s"$hint-b", len2bytes(arr.length))
    }
    object notFound
    @tailrec def get(i: Int, o: Object): Object = o match {
      case s: String => s
      case c: NonSingleCount => get(i, c.item.asInstanceOf[Object])
      case p: Product if i < p.productArity => get(0, p.productElement(i).asInstanceOf[Object])
      case _ => notFound
    }
    worldCheckTraverser.foreachIndex(context, {
      case aI: ee.cone.c4assemble.RIndexImpl => for(bucket <- aI.data) if(idGatherer.findOrAdd(bucket)){
        inc("buckets", 1)
        val is1to1 = bucket.keyPosToValueRange eq ee.cone.c4assemble.OneToOneInnerIndex
        if(is1to1) inc("OneToOne", 1)
        inc(bucket.keys.length match {
          case 0 => "k0" case 1 => "k1" case 2 => "k2" case 3 => "k3"
          case v if v < 10 => "k<10" case v if v < 100 => "k<100" case _ => "k>=100"
        }, 1)
        if(idGatherer.findOrAdd(bucket.keys)){
          incMb("all-id", bucket.keys)
          if(is1to1){
            incMb("all-1to1", bucket.keys)

            val r = if(bucket.keys.indices.forall { i => get(0, bucket.values(i)) == bucket.keys(i) }) "p0"
              else if(bucket.keys.indices.forall { i => get(1, bucket.values(i)) == bucket.keys(i) }) s"p1"
              else if(bucket.keys.indices.forall { i => get(2, bucket.values(i)) == bucket.keys(i) }) s"p2"
              else if(bucket.keys.indices.forall { i => get(3, bucket.values(i)) == bucket.keys(i) }) s"p3"
              else "not"
            incMb(s"can-optimize-$r", bucket.keys)
          }
        }
      }
      case _ => ()
    })
    counts.toSeq.map{ case (k,v) => s"$k ${v.value}"}.sorted.foreach(logger.info(_))
  }
}

/*

@c4("DedupReportApp") final class DedupReporter(
  getOffset: GetOffset, readModelUtil: ReadModelUtil, indexUtil: IndexUtil, config: ListConfig, getFirstborn: GetByPK[S_Firstborn]
) extends WorldCheckHandler with LazyLogging {

  def handle(context: RichContext): Unit = {

    //if( contains )
  }

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
*/


//object CharArrayEq extends Eq {
//  def hash(x: AnyRef): Int = java.util.Arrays.hashCode(x.asInstanceOf[Array[Char]])
//  def same(a: AnyRef, b: AnyRef): Boolean =
//    java.util.Arrays.equals(a.asInstanceOf[Array[Char]], b.asInstanceOf[Array[Char]])
//}

// Single(getFirstborn.ofA(context).values.toSeq).txId == getOffset.of(context)
//@provide def disableDefObserver: Seq[DisableDefObserver] = Seq(new DisableDefObserver)


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