package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.AllTestProtocol.{AllTestOrig, AllTestOrig2}
import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4proto.{Id, Protocol, protocol}

//  C4STATE_TOPIC_PREFIX=ee.cone.c4actor.AllTestTestApp sbt ~'c4actor-extra-examples/runMain ee.cone.c4actor.ServerMain'

@protocol(TestCat) object AllTestProtocol extends Protocol {

  @Id(0x103) case class AllTestOrig(
    @Id(0x104) srcId: String,
    @Id(0x105) value: Int
  )

  @Id(0x106) case class AllTestOrig2(
    @Id(0x104) srcId: String,
    @Id(0x105) value: Int
  )

}

case class AllTestRich(srcId: SrcId, twos: List[AllTestOrig2])

@assemble class AllTestAssemble() extends Assemble {
  type TestAll = All
  type TestSrcId = SrcId

  def AllTestOrigToAll(
    srcId: SrcId,
    one: Each[AllTestOrig]
  ): Values[(TestAll, AllTestOrig)] = List(WithAll(one))

  def AllTestOrig2ToSrcId(
    srcId: SrcId,
    two: Each[AllTestOrig2],
    @by[TestAll] one: Each[AllTestOrig]
  ): Values[(TestSrcId, AllTestOrig2)] =
    if (two.value > one.value)
      List(one.srcId → two)
    else
      Nil

  def AllTestRichToSrcId(
    srcId: SrcId,
    @by[TestAll] one: Each[AllTestOrig],
    @by[TestSrcId] twos: Values[AllTestOrig2]
  ): Values[(SrcId, AllTestRich)] =
    List(WithPK(AllTestRich(one.srcId, twos.toList)))

}

class AllTestTest(
  execution: Execution, toUpdate: ToUpdate, contextFactory: ContextFactory
) extends Executable with LazyLogging {
  def run(): Unit = {
    import LEvent.update
    val emptyLocal = contextFactory.updated(Nil)


    logger.info("============From 0 to 1===================")
    val worldUpdate: collection.immutable.Seq[LEvent[Product]] = List(AllTestOrig("main", 1), AllTestOrig2("test", 2)).flatMap(update)
    val zero = TxAdd(worldUpdate)(emptyLocal)
    println(ByPK(classOf[AllTestRich]).of(zero).values.toList)

    logger.info("============Intermission===================")
    val two = TxAdd(LEvent.update(AllTestOrig("main", 2)))(zero)
    println(ByPK(classOf[AllTestRich]).of(two).values.toList)

    logger.info("============From 0 to 1===================")
    val three = TxAdd(LEvent.update(AllTestOrig2("kek", 3)))(two)
    println(ByPK(classOf[AllTestRich]).of(three).values.toList)

    //logger.info(s"${nGlobal.assembled}")
    execution.complete()
  }
}

class AllTestTestApp extends TestRichDataApp
  with ExecutableApp
  with VMExecutionApp
  with TreeIndexValueMergerFactoryApp
  with ToStartApp {
  override def toStart: List[Executable] = new AllTestTest(execution, toUpdate, contextFactory) :: super.toStart

  override def protocols: List[Protocol] = AllTestProtocol :: super.protocols

  override def assembles: List[Assemble] = new AllTestAssemble() :: super.assembles

  lazy val assembleProfiler = ConsoleAssembleProfiler //ValueAssembleProfiler
}
