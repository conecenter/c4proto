package ee.cone.c4actor.hashing

import java.lang.management.ManagementFactory
import java.util.concurrent.{Callable, Executors}

import com.google.common.base.Charsets
import com.google.common.hash.{HashFunction, Hashing}
import ee.cone.c4actor._
import ee.cone.c4actor.hashing.HashingTestProtocol.{HashOrig, HashTestOrig}
import ee.cone.c4actor.proto.{ProtoBuffAdapters, TestQAdapterRegistryFactory}
import ee.cone.c4proto.{Id, Protocol, protocol}

import scala.util.{Random, Try}

trait HashingAdapters extends ProtocolsApp with QAdapterRegistryApp {

  def qAdapterRegistry: QAdapterRegistry = TestQAdapterRegistryFactory(protocols)

  override def protocols: List[Protocol] = HashingTestProtocol :: AnyOrigProtocol :: QProtocol :: super.protocols
}


/*
This code proves that there is a problem with okio: SegmentPool.java blocks concurrent execution
 */

object HashingTest extends ProtoBuffAdapters {
  def main(args: Array[String]): Unit = {
    println(ManagementFactory.getRuntimeMXBean.getName)
    Thread.sleep(1)
    /*val tasks: Seq[Future[Int]] = for (i <- 1 to 10) yield Future {
      println("Executing task " + i)
      Thread.sleep(i * 1000L)
      i * i
    }

    val aggregated: Future[Seq[Int]] = Future.sequence(tasks)

    val squares: Seq[Int] = Await.result(aggregated, 20.seconds)
    println("Squares: " + squares)*/
      val n = 10
      val iter = 10000

    val times = TimeColored("g", "single thread") {
      for {i ← 1 to n} yield {
        HashingCode.test(iter, qAdapterRegistry)
      }
    }
    println(s"Av ${times.sum / times.length}")

    val runnables = for (i ← 1 to n) yield new HashingRunnable(i, iter, qAdapterRegistry)
    val pool = Executors.newFixedThreadPool(n)
    TimeColored("y", "concurrent") {
      val lul = runnables.map(run ⇒ pool.submit(run))
      println(s"Av2 ${lul.map(_.get()).sum / lul.length}")
      pool.shutdown()
    }
    val hf = Hashing.murmur3_128()
    val hc1 = hf.newHasher()
    .putBoolean(false)
    .putString("123", Charsets.UTF_8)
      .putBoolean(false)
    .putString("567", Charsets.UTF_8)
    .hash()
    val hc2 = hf.newHasher()
      .putBoolean(false)
      .putString("1", Charsets.UTF_8)
      .putBoolean(false)
      .putString("23567", Charsets.UTF_8)
      .hash()
    println(hc1.toString, hc2.toString)
  }
}

class HashingRunnable(pid: Int, number: Int, qAdapterRegistry: QAdapterRegistry) extends Callable[Long] {

  def call(): Long = {
    HashingCode.test(number, qAdapterRegistry)
  }
}

object HashingCode {
  def test(number: Int, qAdapterRegistry: QAdapterRegistry): Long = {
    val time = System.currentTimeMillis()
    val origs = for (i ← 1 to number) yield HashTestOrig(i.toString, i)
    val hashes = origs.map(HashTestOrigHasher.getHash).map(_.toString)

    val bigger = for (i ← 1 to number % 10) yield HashOrig(i.toString, hashes.toList, Try(origs(i)).toOption)
    val hashes2 = bigger.map(HashOrigHasher.getHash).map(_.toString)
    val time2 = System.currentTimeMillis()
    time2 - time
  }
}