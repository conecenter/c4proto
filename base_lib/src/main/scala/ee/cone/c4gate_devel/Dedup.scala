package ee.cone.c4gate_devel

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.{Executable, RichContext, WorldSource}
import ee.cone.c4assemble.{ApproximateInterner, DefInnerIndex, IndexUtil, RIndexImpl, ReadModel, ReadModelUtil, SmallInnerIndex}
import ee.cone.c4di.c4

import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}
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
  worldSource: WorldSource, readModelUtil: ReadModelUtil, indexUtil: IndexUtil,
) extends Executable with LazyLogging {
  private type Q = BlockingQueue[Either[RichContext,Unit]]
  def run(): Unit = {
    val queue: Q = new LinkedBlockingQueue
    worldSource.doWith(queue, () => queue.take() match {
      case Left(world) => report(world.assembled)
      case _ => ()
    })
  }

  private def report(assembled: ReadModel): Unit = {
    val idGatherer = new DedupGatherer(IdentityEq, 29)
    val eqGatherer = new DedupGatherer(ObjectEq, 29)
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
          /*case index: DefInnerIndex =>

            if(idGatherer.findOrAdd(index)){
              idCount += 1
              if(eqGatherer.findOrAdd(index)){
                eqCount += 1
              }
              /*else {
                val seq = ArraySeq.unsafeWrapArray(arr)
                counters(seq) = counters.getOrElse(seq, 0L) + arr.length.toLong
              }*/
            }*/
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
    logger.info(s"ApproximateInterner ${ApproximateInterner.size} ${ApproximateInterner.count()}")


    /*
    logger.info(s"${counters.values.toSeq.sorted}")
    counters.toSeq.sortBy(_._2).takeRight(20).foreach{ case (seq, count) =>
      if(count > 25) logger.info(s"$count ${seq.map(_.toInt)}")
    }*/
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


  }
}


//  private def foreach(a: Array[AnyRef], f: AnyRef => Unit): Unit = a.foreach {
//    case null => ()
//    case w: Array[AnyRef] => foreach(w, f)
//    case w => f(w)
//  }
//
//  def foreach(f: AnyRef => Unit): Unit = foreach(table, f)