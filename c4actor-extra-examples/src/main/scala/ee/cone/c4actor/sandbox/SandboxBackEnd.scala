package ee.cone.c4actor.sandbox

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.PerformanceProtocol.{NodeInstruction, PerformanceNode}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4proto.{Id, Protocol, protocol}

import scala.collection.immutable
import scala.util.Random

case class SrcIdContainer(srcId: SrcId, instrId: SrcId)

case class SrcIdListContainer(srcId: SrcId, list: Values[SrcId])

case class ResultNode(srcId: SrcId, modelsSize: Int, result: String)

case class ResultNodeFromList(srcId: SrcId, modelsSize: Int, result: String)

@assemble class ChangingIndexAssemble(constant: NodeInstruction) extends Assemble {
  type InstructionId = SrcId

  def ModelsToInstruction(
    modelId: SrcId,
    model: Each[PerformanceNode]
  ): Values[(InstructionId, PerformanceNode)] = List(constant.srcId → model)

  def ModelsNInstructionToResult(
    instructionId: SrcId,
    @by[InstructionId] models: Values[PerformanceNode],
    instruction: Each[NodeInstruction]
  ): Values[(InstructionId, SrcIdContainer)] =
    models.slice(instruction.from, instruction.to).map(model ⇒ instruction.srcId → SrcIdContainer(model.srcId, instruction.srcId))

  def ModelsNInstructionToResultList(
    instructionId: SrcId,
    @by[InstructionId] models: Values[PerformanceNode],
    instruction: Each[NodeInstruction]
  ): Values[(InstructionId, SrcIdListContainer)] = {
    val idList = models.slice(instruction.from, instruction.to).map(_.srcId)
    List(WithPK(SrcIdListContainer(instruction.srcId, idList)))
  }

  def CollectResponses(
    instructionId: SrcId,
    @by[InstructionId] srcIdContainers: Values[SrcIdContainer],
    instr: Each[NodeInstruction]
  ): Values[(SrcId, ResultNode)] =
    List(WithPK(ResultNode(instr.srcId, srcIdContainers.size, srcIdContainers.map(_.srcId).groupBy(_.head).keys.toString)))

  def CollectResponsesList(
    instructionId: SrcId,
    @by[InstructionId] list: Each[SrcIdListContainer],
    instr: Each[NodeInstruction]
  ): Values[(SrcId, ResultNodeFromList)] =
    List(WithPK(ResultNodeFromList(instr.srcId, list.list.size, list.list.groupBy(_.head).keys.toString)))
}

class ChangingIndexPerformanceTest(
  execution: Execution, toUpdate: ToUpdate, contextFactory: ContextFactory
) extends Executable with LazyLogging {
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
    println(ByPK(classOf[ResultNodeFromList]).of(firstGlobal))
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println("Change index")
    val secondGlobal = TxAdd(LEvent.update(NodeInstruction("test", worldSize / 2, worldSize)))(firstGlobal)
    println(ByPK(classOf[ResultNode]).of(secondGlobal))
    println(ByPK(classOf[ResultNodeFromList]).of(secondGlobal))
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println("Change index")
    val thirdGlobal = TxAdd(LEvent.update(NodeInstruction("test", 0, 0)))(secondGlobal)
    println(ByPK(classOf[ResultNode]).of(thirdGlobal))
    println(ByPK(classOf[ResultNodeFromList]).of(thirdGlobal))
    execution.complete()
  }
}

class ChangingIndexPerformanceTestApp extends RichDataApp
  with ExecutableApp
  with VMExecutionApp
  with TreeIndexValueMergerFactoryApp
  with ToStartApp {
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

