package ee.cone.c4actor

import java.lang.management.ManagementFactory
import java.util
import java.util.concurrent.{Callable, Executors}

import ee.cone.c4actor.AnyOrigProtocol.N_AnyOrig
import ee.cone.c4proto._

import scala.util.Random
import AnyAdapter._
import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.ProtoBuffTestProtocol.{D_TestOrig, D_TestOrigForDecode}

import scala.collection.immutable

@protocol object ProtoBuffTestProtocolBase   {

  import AnyOrigProtocol._

  @Id(0x1) case class D_TestOrig(
    @Id(0x2) srcId: String,
    @Id(0x3) list: List[String],
    @Id(0x4) byteStr: List[N_AnyOrig]
  )

  @Id(0x5) case class D_TestOrigForDecode(
    @Id(0x6) srcId: String,
    @Id(0x7) number: Long
  )

}

trait Adapters extends ProtocolsApp with QAdapterRegistryApp with BaseApp with ProtoAutoApp {
  lazy val qAdapterRegistry: QAdapterRegistry =
    componentRegistry.resolveSingle(classOf[QAdapterRegistry])
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
      for {i <- 1 to n} yield {
        TestCode.test(iter, qAdapterRegistry)
      }
    }
    println(s"Av ${times.sum / times.length}")

    val runnables = for (i <- 1 to n) yield new SerializationRunnable(i, iter, qAdapterRegistry)
    val pool = Executors.newFixedThreadPool(n)
    TimeColored("y", "concurrent") {
      val lul: immutable.Seq[util.concurrent.Future[Long]] = runnables.map(run => pool.submit(run))
      println(s"Av2 ${lul.map(_.get()).sum / lul.length}")
      pool.shutdown()
    }
  }
}

class SerializationRunnable(pid: Int, number: Int, qAdapterRegistry: QAdapterRegistry) extends Callable[Long] {

  def call(): Long = {
    TestCode.test(number, qAdapterRegistry)
  }
}

object TestCode {
  def test(number: Int, qAdapterRegistry: QAdapterRegistry): Long = {
    val testOrigs = for (i <- 1 to number) yield D_TestOrigForDecode(Random.nextString(10), i)
    val time = System.currentTimeMillis()
    val encoded: immutable.Seq[N_AnyOrig] = testOrigs.map(encode(qAdapterRegistry)(_))
    val testOrigsss: immutable.Seq[D_TestOrig] = encoded.zipWithIndex.map { case (a, b) => D_TestOrig(b.toString, a.toString.split(",").toList, List(a)) }
    val encoded2: immutable.Seq[N_AnyOrig] = testOrigsss.map(encode(qAdapterRegistry)(_))
    val decoded: immutable.Seq[D_TestOrig] = encoded2.map(decode[D_TestOrig](qAdapterRegistry))
    if (testOrigsss != decoded)
      throw new Exception("NOT EQUAL")
    val time2 = System.currentTimeMillis()
    time2 - time
  }
}
