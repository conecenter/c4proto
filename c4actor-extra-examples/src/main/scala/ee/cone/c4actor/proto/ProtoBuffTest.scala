package ee.cone.c4actor.proto

import java.lang.management.ManagementFactory
import java.util
import java.util.concurrent.{Callable, Executors}

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.AnyAdapter.{decode, encode}
import ee.cone.c4actor.AnyOrigProtocol.AnyOrig
import ee.cone.c4actor._
import ee.cone.c4actor.proto.ProtoBuffTestProtocol.{ProtoOrig, TestProtoOrig}
import ee.cone.c4proto._

import scala.collection.immutable
import scala.util.Random

@protocol object ProtoBuffTestProtocol extends Protocol {

  import AnyOrigProtocol._

  @Id(0x1) case class ProtoOrig(
    @Id(0x2) srcId: String,
    @Id(0x3) list: List[String],
    @Id(0x4) byteStr: List[AnyOrig]
  )

  @Id(0x5) case class TestProtoOrig(
    @Id(0x6) srcId: String,
    @Id(0x7) number: Long
  )

}

trait ProtoBuffAdapters extends ProtocolsApp with QAdapterRegistryApp {

  def qAdapterRegistry: QAdapterRegistry = TestQAdapterRegistryFactory(protocols)

  override def protocols: List[Protocol] = ProtoBuffTestProtocol :: AnyOrigProtocol :: QProtocol :: super.protocols
}


/*
This code proves that there is a problem with okio: SegmentPool.java blocks concurrent execution
 */

object ProtoBuffTest extends ProtoBuffAdapters {
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
    val updatesAdapter = byName(classOf[QProtocol.Updates].getName)
      .asInstanceOf[ProtoAdapter[QProtocol.Updates]]
    val byId = CheckedMap(adapters.filter(_.hasId).map(a ⇒ a.id → a))
    new QAdapterRegistry(byName, byId, updatesAdapter)
  }
}

class SerializationRunnable(pid: Int, number: Int, qAdapterRegistry: QAdapterRegistry) extends Callable[Long] {

  def call(): Long = {
    TestCode.test(number, qAdapterRegistry)
  }
}

object TestCode {
  def test(number: Int, qAdapterRegistry: QAdapterRegistry): Long = {
    val testOrigs = for (i ← 1 to number) yield TestProtoOrig(Random.nextString(10), i)
    val time = System.currentTimeMillis()
    val encoded: immutable.Seq[AnyOrig] = testOrigs.map(encode(qAdapterRegistry)(_))
    val testOrigsss: immutable.Seq[ProtoOrig] = encoded.zipWithIndex.map { case (a, b) ⇒ ProtoOrig(b.toString, a.toString.split(",").toList, List(a)) }
    val encoded2: immutable.Seq[AnyOrig] = testOrigsss.map(encode(qAdapterRegistry)(_))
    val decoded: immutable.Seq[ProtoOrig] = encoded2.map(decode[ProtoOrig](qAdapterRegistry))
    if (testOrigsss != decoded)
      throw new Exception("NOT EQUAL")
    val time2 = System.currentTimeMillis()
    time2 - time
  }
}
