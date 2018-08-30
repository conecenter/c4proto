package ee.cone.c4actor.sandbox

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.PerformanceProtocol.{NodeInstruction, PerformanceNode}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.sandbox.SandboxProtocol.SandboxOrig
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4proto.{Id, Protocol, protocol}

import scala.collection.immutable
import scala.util.Random

/*
  To start this app type the following into console:
  C4STATE_TOPIC_PREFIX=ee.cone.c4actor.sandbox.SandboxProject sbt ~'c4actor-extra-examples/runMain ee.cone.c4actor.ServerMain'
 */

class ChangingIndexPerformanceTest(
  execution: Execution, toUpdate: ToUpdate, contextFactory: ContextFactory
) extends Executable with LazyLogging {
  def run(): Unit = {
    import LEvent.update
    // val updates: List[QProtocol.Update] = worldUpdate.map(rec ⇒ toUpdate.toUpdate(rec)).toList
    val local: Context = contextFactory.create()
    //val nGlobal: Context = ReadModelAddKey.of(context)(updates)(context)
    val neededSrcId = "123"

    val sandboxOrigMap: Map[SrcId, SandboxOrig] = ByPK(classOf[SandboxOrig]).of(local)
    val someOrig: Option[SandboxOrig] = sandboxOrigMap.get(neededSrcId)


    println(someOrig)
    execution.complete()
  }
}

class SandboxProject extends RichDataApp
  with ExecutableApp
  with VMExecutionApp
  with TreeIndexValueMergerFactoryApp
  with ToStartApp
  with SandboxProtocolsApp
  with SandboxJoinersApp {
  override def toStart: List[Executable] = new ChangingIndexPerformanceTest(execution, toUpdate, contextFactory) :: super.toStart

  lazy val assembleProfiler = ValueAssembleProfiler
}

object ValueAssembleProfiler extends AssembleProfiler {
  def get(ruleName: String): String ⇒ Int ⇒ Unit = startAction ⇒ {
    val startTime = System.currentTimeMillis
    finalCount ⇒ {
      val period = System.currentTimeMillis - startTime
      println(s"assembling by ${Thread.currentThread.getName} rule $ruleName $startAction $finalCount items in $period ms")
    }
  }
}

