package ee.cone.c4actor.sandbox

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.sandbox.SandboxProtocol.D_Sandbox
import ee.cone.c4di.{c4, c4app}

/*
  To start this app type the following into console:
  C4STATE_TOPIC_PREFIX=ee.cone.c4actor.sandbox.SandboxProject sbt ~'c4actor-extra-examples/runMain ee.cone.c4actor.ServerMain'
 */

@c4("SandboxProjectApp") class SandboxProject(
  execution: Execution, toUpdate: ToUpdate, contextFactory: ContextFactory,
  getD_Sandbox: GetByPK[D_Sandbox],
) extends Executable with LazyLogging {
  def run(): Unit = {
    // val updates: List[QProtocol.N_Update] = worldUpdate.map(rec => toUpdate.toUpdate(rec)).toList
    val local: Context = contextFactory.updated(Nil)
    //val nGlobal: Context = ReadModelAddKey.of(context)(updates)(context)
    val neededSrcId = "123"

    val sandboxOrigMap: Map[SrcId, D_Sandbox] = getD_Sandbox.ofA(local)
    val someOrig: Option[D_Sandbox] = sandboxOrigMap.get(neededSrcId)


    println(someOrig)
    execution.complete()
  }
}

@c4app trait SandboxProjectAppBase extends TestVMRichDataApp
  with ExecutableApp
  with VMExecutionApp
  with SandboxProtocolsApp
  with SandboxJoinersApp
{
  lazy val assembleProfiler: AssembleProfiler = NoAssembleProfiler //ValueAssembleProfiler
}
/*
object ValueAssembleProfiler extends AssembleProfiler {
  def get(ruleName: String): String => Int => Unit = startAction => {
    val startTime = System.currentTimeMillis
    finalCount => {
      val period = System.currentTimeMillis - startTime
      println(s"assembling by ${Thread.currentThread.getName} rule $ruleName $startAction $finalCount items in $period ms")
    }
  }
}
*/
