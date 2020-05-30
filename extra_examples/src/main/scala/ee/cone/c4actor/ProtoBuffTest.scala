package ee.cone.c4actor

import java.lang.management.ManagementFactory
import java.util
import java.util.concurrent.{Callable, Executors}

import ee.cone.c4actor.AnyAdapter._
import ee.cone.c4actor.AnyOrigProtocol.N_AnyOrig
import ee.cone.c4actor.ProtoBuffTestProtocol.{D_TestOrig, D_TestOrigForDecode}
import ee.cone.c4di.{c4, c4app}
import ee.cone.c4proto._

import scala.collection.immutable
import scala.util.Random

trait ProtoBuffTestProtocolAppBase

@protocol("ProtoBuffTestProtocolApp") object ProtoBuffTestProtocol {

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

@c4app class SeqProtoBuffTestAppBase extends ProtoBuffTestApp
@c4app class ParProtoBuffTestAppBase extends ProtoBuffTestApp

trait ProtoBuffTestApp
  extends VMExecutionApp with ExecutableApp
    with BaseApp with ProtoApp
    with ProtoBuffTestProtocolApp
    with AnyOrigProtocolApp

/*
This code proves that there is a problem with okio: SegmentPool.java blocks concurrent execution
 */

object ProtoBuffTest {
  val n = 25
  val iter = 40000
  val testOrigs: Seq[D_TestOrigForDecode] = for (i <- 1 to iter) yield D_TestOrigForDecode(Random.nextString(10), i)
}

@c4("SeqProtoBuffTestApp") final class SeqProtoBuffTest(
  qAdapterRegistry: QAdapterRegistry,
  execution: Execution,
) extends Executable {
  def run(): Unit = {
    import ProtoBuffTest._
    println("started")
    for(_ <- 1 to 10) {
      val times = TimeColored("g", "single thread") {
        for {i <- 1 to n} yield {
          TestCode.test(testOrigs, qAdapterRegistry)
        }
      }
      println(s"Av ${times.sum / times.length}")
      TimeColored("g", "gc") {
        System.gc()
      }
    }

    execution.complete()
  }
}

@c4("ParProtoBuffTestApp") final class ParProtoBuffTest(
  qAdapterRegistry: QAdapterRegistry,
  execution: Execution,
) extends Executable {
  def run(): Unit = {
    import ProtoBuffTest._
    println("started")

    for(_ <- 1 to 10) {
      val runnables = for (i <- 1 to n) yield new SerializationRunnable(
        i,
        testOrigs,
        qAdapterRegistry
      )
      val pool = Executors.newFixedThreadPool(n)
      TimeColored("y", "concurrent") {
        val lul: immutable.Seq[util.concurrent.Future[Long]] = runnables
          .map(run => pool.submit(run))
        println(s"Av2 ${lul.map(_.get()).sum / lul.length}")
        pool.shutdown()
      }
      TimeColored("g", "gc") {
        System.gc()
      }
    }

    execution.complete()
  }
}

//Thread.sleep(10000)
/*val tasks: Seq[Future[Int]] = for (i <- 1 to 10) yield Future {
  println("Executing task " + i)
  Thread.sleep(i * 1000L)
  i * i
}

val aggregated: Future[Seq[Int]] = Future.sequence(tasks)

val squares: Seq[Int] = Await.result(aggregated, 20.seconds)
println("Squares: " + squares)*/

class SerializationRunnable(pid: Int, testOrigs: Seq[D_TestOrigForDecode], qAdapterRegistry: QAdapterRegistry) extends Callable[Long] {

  def call(): Long = {
    TestCode.test(testOrigs, qAdapterRegistry)
  }
}

object TestCode {
  def test(testOrigs: Seq[D_TestOrigForDecode], qAdapterRegistry: QAdapterRegistry): Long = {
    val time = System.currentTimeMillis()
    val encoded: immutable.Seq[N_AnyOrig] = testOrigs.map(encode(qAdapterRegistry)(_))
    val testOrigsss: immutable.Seq[D_TestOrig] = encoded.zipWithIndex.map { case (a, b) => D_TestOrig(b.toString, a.toString.split(",").toList, List(a)) }
    val encoded2: immutable.Seq[N_AnyOrig] = testOrigsss.map(encode(qAdapterRegistry)(_))
    val decoded: immutable.Seq[D_TestOrig] = encoded2.map(decode[D_TestOrig](qAdapterRegistry))
    // assert (testOrigsss == decoded)
    val time2 = System.currentTimeMillis()
    time2 - time
  }
}
