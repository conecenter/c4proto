package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.PerformanceProtocol.{NodeInstruction, PerformanceNode}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
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

case class Test[Model, Model2](model: Model, model2: Model2)

case class Test2[Model, Model2](model: Model, model2: Model2)

@assemble class LUL[Model, Model2](modelCl: Class[Model], model2: Class[Model2]) extends Assemble {

  def test(
    modelId: SrcId,
    model: Each[Test[Model, Model2]]
  ): Values[(SrcId, Test2[Model, Model2])] = {
    println(modelCl, model2, model)
    WithPK(Test2(model.model, model.model2)) :: Nil
  }

}

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

  def test3(
    modelId: SrcId,
    firstb: Each[PerformanceNode]
  ): Values[(SrcId, Test[PerformanceNode, NodeInstruction])] =
    if (firstb.srcId.toInt < 10)
      WithPK(Test(firstb, NodeInstruction(firstb.srcId, 1, 1))) :: Nil
    else Nil

  def test4(
    modelId: SrcId,
    firstb: Each[PerformanceNode]
  ): Values[(SrcId, Test[String, NodeInstruction])] =
    if (firstb.srcId.toInt < 10)
      WithPK(Test(firstb.srcId, NodeInstruction(firstb.srcId, 1, 1))) :: Nil
    else Nil

  def test5(
    modelId: SrcId,
    firstb: Each[PerformanceNode]
  ): Values[(SrcId, Test[PerformanceNode, Int])] =
    if (firstb.srcId.toInt < 10)
      WithPK(Test(firstb, 1)) :: Nil
    else Nil


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
      } yield PerformanceNode(i.toString, Random.nextDouble().toString)
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

  override def protocols: List[Protocol] = PerformanceProtocol :: super.protocols

  override def assembles: List[Assemble] = new LUL(classOf[PerformanceNode], classOf[NodeInstruction]) :: new LUL(classOf[String], classOf[NodeInstruction]) :: new ChangingIndexAssemble(NodeInstruction("test", 0, 25000)) :: super.assembles

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

  override def getOpt(ruleName: String, in: immutable.Seq[AssembledKey], out: AssembledKey): Option[String ⇒ Int ⇒ Unit] =
    Some { startAction ⇒
      val startTime = System.currentTimeMillis
      finalCount ⇒ {
        val period = System.currentTimeMillis - startTime
        println(s"assembling by ${Thread.currentThread.getName} rule $ruleName\nin:${in.mkString("|")}\nout:$out\n$startAction $finalCount items in $period ms")
      }
    }
}
