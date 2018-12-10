package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.OrigConflictProtocol.ConflictOrig
import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, Single, assemble}
import ee.cone.c4proto.{Id, Protocol, protocol}

//  C4STATE_TOPIC_PREFIX=ee.cone.c4actor.ConflictOrigTestApp sbt ~'c4actor-extra-examples/runMain ee.cone.c4actor.ServerMain'

@protocol(TestCat) object OrigConflictProtocol extends Protocol {

  @Id(0x103) case class ConflictOrig(
    @Id(0x104) srcId: String,
    @Id(0x105) value: Int
  )

}

case class ConflictRich(conflict: ConflictOrig)

@assemble class OrigConflictAssemble(produce: ConflictOrig) extends Assemble {

  def Produce(
    modelId: SrcId,
    fb: Each[Firstborn]
  ): Values[(SrcId, ConflictOrig)] =
    WithPK(produce) :: Nil

  def ProduceRich(
    modelId: SrcId,
    origs: Values[ConflictOrig]
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
    val emptyLocal = contextFactory.updated(Nil)

    logger.info("============Empty local===================")
    println(ByPK(classOf[ConflictRich]).of(emptyLocal).values.toList)

    val worldUpdate: Seq[LEvent[Product]] = List(ConflictOrig("main", 2)).flatMap(update)
    val updates: List[QProtocol.Update] = worldUpdate.map(rec ⇒ toUpdate.toUpdate(rec)).toList
    val nonEmptyLocal = contextFactory.updated(updates)

    logger.info("============Non empty local===================")
    println(ByPK(classOf[ConflictRich]).of(nonEmptyLocal).values.toList)

    //logger.info(s"${nGlobal.assembled}")
    execution.complete()
  }
}

class ConflictOrigTestApp extends TestRichDataApp
  with ExecutableApp
  with VMExecutionApp
  with TreeIndexValueMergerFactoryApp
  with ToStartApp {
  override def toStart: List[Executable] = new ConflictingOrigTest(execution, toUpdate, contextFactory) :: super.toStart

  override def protocols: List[Protocol] = OrigConflictProtocol :: super.protocols

  override def assembles: List[Assemble] = new OrigConflictAssemble(ConflictOrig("main", 0)) :: super.assembles

  lazy val assembleProfiler = ConsoleAssembleProfiler //ValueAssembleProfiler
}