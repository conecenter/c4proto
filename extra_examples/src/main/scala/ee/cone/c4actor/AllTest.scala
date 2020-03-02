package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.AllTestProtocol.{D_AllTestOrig, D_AllTestOrig2}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4di.{c4, c4app}
import ee.cone.c4proto.{Id, protocol}

//  C4STATE_TOPIC_PREFIX=ee.cone.c4actor.AllTestTestApp ./app.pl sbt ~'c4actor-extra-examples/runMain ee.cone.c4actor.ServerMain'

trait AllTestProtocolAppBase

@protocol("AllTestTestApp") object AllTestProtocol {

  @Id(0x103) case class D_AllTestOrig(
    @Id(0x104) srcId: String,
    @Id(0x105) value: Int
  )

  @Id(0x106) case class D_AllTestOrig2(
    @Id(0x104) srcId: String,
    @Id(0x105) value: Int
  )

}

case class AllTestRich(srcId: SrcId, twos: List[D_AllTestOrig2])

@c4assemble("AllTestTestApp") class AllTestAssembleBase {
  type TestAll = AbstractAll
  type TestSrcId = SrcId

  def D_AllTestOrigToAll(
    srcId: SrcId,
    one: Each[D_AllTestOrig]
  ): Values[(TestAll, D_AllTestOrig)] = List(WithAll(one))

  def D_AllTestOrig2ToSrcId(
    srcId: SrcId,
    two: Each[D_AllTestOrig2],
    @byEq[TestAll](All) one: Each[D_AllTestOrig]
  ): Values[(TestSrcId, D_AllTestOrig2)] =
    if (two.value > one.value)
      List(one.srcId -> two)
    else
      Nil

  def AllTestRichToSrcId(
    srcId: SrcId,
    @byEq[TestAll](All) one: Each[D_AllTestOrig],
    @by[TestSrcId] twos: Values[D_AllTestOrig2]
  ): Values[(SrcId, AllTestRich)] =
    List(WithPK(AllTestRich(one.srcId, twos.toList)))

}

@c4("AllTestTestApp") class AllTestTest(
  execution: Execution, toUpdate: ToUpdate, contextFactory: ContextFactory,
  getAllTestRich: GetByPK[AllTestRich]
) extends Executable with LazyLogging {
  def run(): Unit = {
    import LEvent.update
    val emptyLocal = contextFactory.updated(Nil)


    logger.info("============From 0 to 1===================")
    val worldUpdate: collection.immutable.Seq[LEvent[Product]] = List(D_AllTestOrig("main", 1), D_AllTestOrig2("test", 2)).flatMap(update)
    val zero = TxAdd(worldUpdate)(emptyLocal)
    println(getAllTestRich.ofA(zero).values.toList)

    logger.info("============Intermission===================")
    val two = TxAdd(LEvent.update(D_AllTestOrig("main", 2)))(zero)
    println(getAllTestRich.ofA(two).values.toList)

    logger.info("============From 0 to 1===================")
    val three = TxAdd(LEvent.update(D_AllTestOrig2("kek", 3)))(two)
    println(getAllTestRich.ofA(three).values.toList)

    //logger.info(s"${nGlobal.assembled}")
    execution.complete()
  }
}

@c4app class AllTestTestAppBase extends TestVMRichDataApp
  with ExecutableApp
  with VMExecutionApp
  with ToStartApp
{
  lazy val assembleProfiler = ConsoleAssembleProfiler //ValueAssembleProfiler
}
