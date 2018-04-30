package ee.cone.c4actor

import java.util.UUID

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.PerformanceProtocol.{NodeInstruction, PerformanceNode}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4proto.{Id, Protocol, protocol}

import scala.collection.immutable
import scala.util.Random

//  C4STATE_TOPIC_PREFIX=ee.cone.c4actor.ChangingIndexPerformanceTestApp sbt ~'c4actor-extra-examples/runMain ee.cone.c4actor.ServerMain'

@protocol object PerformanceProtocol extends Protocol {

  @Id(0x0100) case class PerformanceNode(
    @Id(0x0101) srcId: String,
    @Id(0x0102) value: String
  )

  @Id(0x103) case class NodeInstruction(
    @Id(0x104) srcId: String,
    @Id(0x105) from: Int,
    @Id(0x106) to: Int
  )

}

case class SrcIdContainer(instrId: SrcId, srcId: SrcId)

case class ResultNode(srcId: SrcId, modelsSize: Int, result: String)

@assemble class ChangingIndexAssemble(instruction: NodeInstruction) extends Assemble {
  type InstructionId = SrcId

  def ModelsToInstruction(
    modelId: SrcId,
    models: Values[PerformanceNode]
  ): Values[(InstructionId, PerformanceNode)] =
    for {
      model ← models
    } yield instruction.srcId → model

  def ModelsNInstructionToResult(
    instructionId: SrcId,
    @by[InstructionId] models: Values[PerformanceNode],
    instructions: Values[NodeInstruction]
  ): Values[(InstructionId, SrcIdContainer)] =
    (for {
      instruction ← instructions
    } yield {
      models.slice(instruction.from, instruction.to).map(model ⇒ WithPK(SrcIdContainer(instruction.srcId, model.srcId)))
    }).flatten

  def CollectResponses(
    instructionId: SrcId,
    @by[InstructionId] srcIdContainers: Values[SrcIdContainer],
    instructions: Values[NodeInstruction]
  ): Values[(SrcId, ResultNode)] =
    for {
      instr ← instructions
    } yield WithPK(ResultNode(instr.srcId, srcIdContainers.size, srcIdContainers.map(_.srcId).groupBy(_.head).keys.toString))
}

class ChangingIndexPerformanceTest(
  execution: Execution, toUpdate: ToUpdate, contextFactory: ContextFactory
) extends Executable with LazyLogging with TestCondition {
  def run(): Unit = {
    import LEvent.update
    val worldSize = 100000
    val world: immutable.Seq[PerformanceNode] =
      for {
        i ← 1 to worldSize
      } yield PerformanceNode(i.toString, Random.nextString(5))
    val worldUpdate: immutable.Seq[LEvent[PerformanceNode]] = world.flatMap(update)
    val updates: List[QProtocol.Update] = worldUpdate.map(rec ⇒ toUpdate.toUpdate(rec)).toList
    val context: Context = contextFactory.create()
    val nGlobal: Context = ReadModelAddKey.of(context)(updates)(context)

    //logger.info(s"${nGlobal.assembled}")

    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println("World Ready")
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println("Change index")
    val firstGlobal = TxAdd(LEvent.update(NodeInstruction("test", 0, worldSize / 2)))(nGlobal)
    println(ByPK(classOf[ResultNode]).of(firstGlobal))
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println("Change index")
    val secondGlobal = TxAdd(LEvent.update(NodeInstruction("test", worldSize / 2, worldSize)))(firstGlobal)
    println(ByPK(classOf[ResultNode]).of(secondGlobal))
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println("Change index")
    val thirdGlobal = TxAdd(LEvent.update(NodeInstruction("test", worldSize / 4, 3 * worldSize / 4)))(secondGlobal)
    println(ByPK(classOf[ResultNode]).of(thirdGlobal))
    execution.complete()
  }
}

class ChangingIndexPerformanceTestApp extends RichDataApp
  with ExecutableApp
  with VMExecutionApp
  with TreeIndexValueMergerFactoryApp
  with ToStartApp
  with MortalFactoryApp {
  override def toStart: List[Executable] = new ChangingIndexPerformanceTest(execution, toUpdate, contextFactory) :: super.toStart

  override def protocols: List[Protocol] = PerformanceProtocol :: super.protocols

  override def assembles: List[Assemble] = new ChangingIndexAssemble(NodeInstruction("test", 0, 25000)) :: super.assembles

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
