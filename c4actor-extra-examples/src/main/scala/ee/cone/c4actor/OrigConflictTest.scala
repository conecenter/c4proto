package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.ProtoConflict.D_ConflictOrig
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, Single, assemble}
import ee.cone.c4proto.{Id, Protocol, protocol}

//  C4STATE_TOPIC_PREFIX=ee.cone.c4actor.ConflictOrigTestApp sbt ~'c4actor-extra-examples/runMain -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 ee.cone.c4actor.ServerMain'

@protocol(TestCat) object ProtoConflictBase   {

  @Id(0x103) case class D_ConflictOrig(
    @Id(0x104) srcId: String,
    @Id(0x105) value: Int
  )

}

case class ConflictRich(conflict: D_ConflictOrig)

@assemble class AssembleConflictBase(produce: D_ConflictOrig)   {

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
      origg ← Single.option(origs).toList
    } yield
      WithPK(ConflictRich(origg))

}

class ConflictingOrigTest(
  execution: Execution, toUpdate: ToUpdate, contextFactory: ContextFactory
) extends Executable with LazyLogging {
  def run(): Unit = {
    import LEvent.update
    println("DEBUG ME NOWWWW")
    Thread.sleep(5000)
    val emptyLocal = contextFactory.updated(Nil)


    logger.info("============Empty local===================")
    println(ByPK(classOf[ConflictRich]).of(emptyLocal).values.toList)

    val worldUpdate: Seq[LEvent[Product]] = List(D_ConflictOrig("main", 2)).flatMap(update)
    val updates: List[QProtocol.N_Update] = worldUpdate.map(rec ⇒ toUpdate.toUpdate(rec)).toList
    val nonEmptyLocal = contextFactory.updated(updates)

    logger.info("============Non empty local===================")
    println(ByPK(classOf[ConflictRich]).of(nonEmptyLocal).values.toList)

    //logger.info(s"${nGlobal.assembled}")
    execution.complete()
  }
}

class D_ConflictOrigTestApp extends TestRichDataApp
  with ExecutableApp
  with VMExecutionApp
  with TreeIndexValueMergerFactoryApp
  with ToStartApp {
  override def toStart: List[Executable] = new ConflictingOrigTest(execution, toUpdate, contextFactory) :: super.toStart

  override def protocols: List[Protocol] = ProtoConflict :: super.protocols

  override def assembles: List[Assemble] = new AssembleConflict(D_ConflictOrig("main", 0)) :: super.assembles

  lazy val assembleProfiler = ConsoleAssembleProfiler //ValueAssembleProfiler
}