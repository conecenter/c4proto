package ee.cone.c4actor

import java.lang.management.ManagementFactory
import java.util
import java.util.concurrent.{Callable, Executors}

import ee.cone.c4actor.AnyOrigProtocol.AnyOrig
import ee.cone.c4proto._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.util.Random
import AnyAdapter._
import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.ProtoBuffTestProtocol.{TestOrig, TestOrigForDecode}

import scala.collection.immutable

@protocol(TestOrigCat) object ProtoBuffTestProtocol extends Protocol {

  import AnyOrigProtocol._

  @Id(0x1) case class TestOrig(
    @Id(0x2) srcId: String,
    @Id(0x3) list: List[String],
    @Id(0x4) byteStr: List[AnyOrig]
  )

  @Id(0x5) case class TestOrigForDecode(
    @Id(0x6) srcId: String,
    @Id(0x7) number: Long
  )

}

trait Adapters extends ProtocolsApp with QAdapterRegistryApp {

  def qAdapterRegistry: QAdapterRegistry = TestQAdapterRegistryFactory(protocols)

  override def protocols: List[Protocol] = ProtoBuffTestProtocol :: AnyOrigProtocol :: QProtocol :: super.protocols
}


/*
This code proves that there is a problem with okio: SegmentPool.java blocks concurrent execution
 */

object ProtoBuffTest extends Adapters {
  def main(args: Array[String]): Unit = {
    println(ManagementFactory.getRuntimeMXBean.getName)
    Thread.sleep(10000)
    /*val tasks: Seq[Future[Int]] = for (i <- 1 to 10) yield Future {
      println("Executing task " + i)
      Thread.sleep(i * 1000L)
      i * i
    }

    val aggregated: Future[Seq[Int]] = Future.sequence(tasks)

    val squares: Seq[Int] = Await.result(aggregated, 20.seconds)
    println("Squares: " + squares)*/
    val n = 100
    val iter = 10000

    val times = TimeColored("g", "single thread") {
      for {i ← 1 to n} yield {
        TestCode.test(iter, qAdapterRegistry)
      }
    }
    println(s"Av ${times.sum / times.length}")

    val runnables = for (i ← 1 to n) yield new SerializationRunnable(i, iter, qAdapterRegistry)
    val pool = Executors.newFixedThreadPool(n)
    TimeColored("y", "concurrent") {
      val lul: immutable.Seq[util.concurrent.Future[Long]] = runnables.map(run ⇒ pool.submit(run))
      println(s"Av2 ${lul.map(_.get()).sum / lul.length}")
      pool.shutdown()
    }
  }
}

object TestQAdapterRegistryFactory {
  def apply(protocols: List[Protocol]): QAdapterRegistry = {
    val adapters = protocols.flatMap(_.adapters).asInstanceOf[List[ProtoAdapter[Product] with HasId]]
    val byName = CheckedMap(adapters.map(a ⇒ a.className → a))
    val byId = CheckedMap(adapters.filter(_.hasId).map(a ⇒ a.id → a))
    new QAdapterRegistry(byName, byId)
  }
}

class SerializationRunnable(pid: Int, number: Int, qAdapterRegistry: QAdapterRegistry) extends Callable[Long] {

  def call(): Long = {
    TestCode.test(number, qAdapterRegistry)
  }
}

object TestCode {
  def test(number: Int, qAdapterRegistry: QAdapterRegistry): Long = {
    val testOrigs = for (i ← 1 to number) yield TestOrigForDecode(Random.nextString(10), i)
    val time = System.currentTimeMillis()
    val encoded: immutable.Seq[AnyOrig] = testOrigs.map(encode(qAdapterRegistry)(_))
    val testOrigsss: immutable.Seq[TestOrig] = encoded.zipWithIndex.map { case (a, b) ⇒ TestOrig(b.toString, a.toString.split(",").toList, List(a)) }
    val encoded2: immutable.Seq[AnyOrig] = testOrigsss.map(encode(qAdapterRegistry)(_))
    val decoded: immutable.Seq[TestOrig] = encoded2.map(decode[TestOrig](qAdapterRegistry))
    if (testOrigsss != decoded)
      throw new Exception("NOT EQUAL")
    val time2 = System.currentTimeMillis()
    time2 - time
  }
}
