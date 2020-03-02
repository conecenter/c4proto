package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.ProtoConflict.D_ConflictOrig
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, Single, assemble}
import ee.cone.c4di.{c4, c4app}
import ee.cone.c4proto.{Id, protocol}

//  C4STATE_TOPIC_PREFIX=ee.cone.c4actor.ConflictOrigTestApp sbt ~'c4actor-extra-examples/runMain -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 ee.cone.c4actor.ServerMain'

trait ProtoConflictAppBase

@protocol("ProtoConflictApp") object ProtoConflict {

  @Id(0x103) case class D_ConflictOrig(
    @Id(0x104) srcId: String,
    @Id(0x105) value: Int
  )

}

case class ConflictRich(conflict: D_ConflictOrig)

@assemble class AssembleConflictBase(produce: D_ConflictOrig) {

  def Produce(
    modelId: SrcId,
    fb: Each[S_Firstborn]
  ): Values[(SrcId, D_ConflictOrig)] =
    WithPK(produce) :: Nil

  def ProduceRich(
    modelId: SrcId,
    origs: Values[D_ConflictOrig]
  ): Values[(SrcId, ConflictRich)] =
    for {
      origg <- Single.option(origs).toList
    } yield
      WithPK(ConflictRich(origg))

}

@c4("D_ConflictOrigTestApp") class ConflictingOrigTest(
  execution: Execution, toUpdate: ToUpdate, contextFactory: ContextFactory,
  getConflictRich: GetByPK[ConflictRich],
) extends Executable with LazyLogging {
  def run(): Unit = {
    import LEvent.update
    println("DEBUG ME NOWWWW")
    Thread.sleep(5000)
    val emptyLocal = contextFactory.updated(Nil)


    logger.info("============Empty local===================")
    println(getConflictRich.ofA(emptyLocal).values.toList)

    val worldUpdate: Seq[LEvent[Product]] = List(D_ConflictOrig("main", 2)).flatMap(update)
    val updates: List[QProtocol.N_Update] = worldUpdate.map(rec => toUpdate.toUpdate(rec)).toList
    val nonEmptyLocal = contextFactory.updated(updates)

    logger.info("============Non empty local===================")
    println(getConflictRich.ofA(nonEmptyLocal).values.toList)

    //logger.info(s"${nGlobal.assembled}")
    execution.complete()
  }
}

@c4app trait D_ConflictOrigTestAppBase extends TestVMRichDataApp
  with ExecutableApp
  with VMExecutionApp
  with ProtoConflictApp
{
  override def assembles: List[Assemble] = new AssembleConflict(D_ConflictOrig("main", 0)) :: super.assembles

  lazy val assembleProfiler = ConsoleAssembleProfiler //ValueAssembleProfiler
}