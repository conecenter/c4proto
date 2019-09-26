package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.TypedAllTestProtocol.{D_Model1, D_Model2, D_ModelTest}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4proto.{Id, Protocol, protocol}

//  C4STATE_TOPIC_PREFIX=ee.cone.c4actor.TypedAllTestApp sbt ~'c4actor-extra-examples/runMain ee.cone.c4actor.ServerMain'
class TypedAllTestStart(
  execution: Execution,
  toUpdate: ToUpdate,
  contextFactory: ContextFactory,
  //rawWorldFactory: RichRawWorldFactory, /* progressObserverFactory: ProgressObserverFactory,*/
  //observer: Option[Observer],
  qAdapterRegistry: QAdapterRegistry
) extends Executable with LazyLogging {
  def run(): Unit = {
    import LEvent.update
    val recs = update(S_Firstborn("test", "0" * OffsetHexSize())) ++ update(D_Model1("1")) ++ update(D_Model2("2"))
    val updates: List[QProtocol.N_Update] = recs.map(rec => toUpdate.toUpdate(rec)).toList
    val nGlobal = contextFactory.updated(updates)
    val nGlobalActive = ActivateContext(nGlobal)
    val nGlobalAA = ActivateContext(nGlobalActive)
    val nGlobalAAA = ActivateContext(nGlobalAA)
    val nGlobalAAAA = ActivateContext(nGlobalAAA)

    //logger.info(s"${nGlobal.assembled}")
    println("0<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")
    //println(ByPK(classOf[D_TestObject]).of(nGlobal).values.toList)
    println("1>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")
    println("1<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")
    //println(ByPK(classOf[D_TestObject]).of(newNGlobal).values.toList)
    println("2>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")
    val test = TxAdd(update(D_ModelTest("1", 1)) ++ update(D_ModelTest("2", 2)))(nGlobalAAAA)
    println("2<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")
    val test2 = TxAdd(update(D_ModelTest("1", 3)) ++ update(D_ModelTest("3", 5)) ++ update(D_ModelTest("2", 2)))(test)
    //println(ByPK(classOf[D_TestObject]).of(newNGlobal).values.toList)
    execution.complete()

  }
}

trait TypedAllType {
  type TestAll[T] = AbstractAll
  type TestBy[T] = SrcId
}

@assemble class TestAllEachBase   {
  type FixedAll = AbstractAll

  def test1(
    srcId: SrcId,
    test: Each[D_ModelTest]
  ): Values[(FixedAll, D_ModelTest)] = (All -> test) :: Nil

  def test2(
    srcId: SrcId,
    firstborn: Each[S_Firstborn],
    @byEq[FixedAll](All) test: Each[D_ModelTest]
  ): Values[(SrcId, Nothing)] = {
    println(test)
    Nil
  }

  def test3(
    srcId: SrcId,
    firstborn: Each[S_Firstborn],
    @byEq[FixedAll](All) test: Values[D_ModelTest]
  ): Values[(SrcId, Nothing)] = {
    println(test)
    Nil
  }
}

@assemble class TypedAllTestAssembleBase[Model <: Product](modelCl: Class[Model]) extends   TypedAllType {

  def ModelToTypedAll(
    srcId: SrcId,
    models: Values[Model]
  ): Values[(TestAll[Model], Model)] =
    for {
      model <- models
    } yield All -> model

  def AllGrabber(
    srcId: SrcId,
    firstborn: Values[S_Firstborn],
    @byEq[TestAll[Model]](All) models: Values[Model]
  ): Values[(SrcId, Nothing)] = {
    println(s"[TYPED,$modelCl]", models)
    Nil
  }

  type FixedAll = AbstractAll

  def ModelToFixedAll(
    srcId: SrcId,
    models: Values[Model]
  ): Values[(FixedAll, Model)] =
    for {
      model <- models
    } yield All -> model

  def FixedAllGrabber(
    srcId: SrcId,
    firstborn: Each[S_Firstborn],
    @byEq[FixedAll](All) models: Values[Model]
  ): Values[(SrcId, Nothing)] = {
    println(s"[FIXED,$modelCl]", models)
    Nil
  }

  def ModelToFixedAll2(
    srcId: SrcId,
    models: Values[Model]
  ): Values[(TestBy[Model], Model)] =
    for {
      model <- models
    } yield "test" -> model

  def FixedAllGrabber2(
    srcId: SrcId,
    firstborn: Values[S_Firstborn],
    @by[TestBy[Model]] models: Values[Model]
  ): Values[(SrcId, Nothing)] = {
    println(s"[FIXEDBy,$modelCl]", models)
    Nil
  }

  def CreateTx(
    srcId: SrcId,
    firstborn: Each[S_Firstborn],
    @byEq[FixedAll](All) models: Values[Model]
  ): Values[(SrcId, TxTransform)] = WithPK(TestTx(srcId + modelCl.getName)) :: Nil
}

case class TestTx(srcId: SrcId) extends TxTransform {
  def transform(local: Context): Context = TxAdd(LEvent.update(D_Model2(srcId)))(local)
}


@protocol object TypedAllTestProtocolBase   {

  @Id(0xaabc) case class D_Model1(
    @Id(0xaabd) srcId: String
  )

  @Id(0x4567) case class D_Model2(
    @Id(0xabcd) srcId: String
  )

  @Id(0x789a) case class D_ModelTest(
    @Id(0x1234) srcId: String,
    @Id(0x1235) value: Int
  )

}

class TypedAllTestApp extends TestVMRichDataApp
  //with ServerApp
  //with EnvConfigApp
  with VMExecutionApp
  //with ParallelObserversApp
  //with FileRawSnapshotApp
  with TreeIndexValueMergerFactoryApp
  with ExecutableApp
  with ToStartApp {


  //override def rawQSender: RawQSender = NoRawQSender

  override def parallelAssembleOn: Boolean = true

  override def toStart: List[Executable] = new TypedAllTestStart(execution, toUpdate, contextFactory, /*txObserver,*/ qAdapterRegistry) :: super.toStart


  override def protocols: List[Protocol] = TypedAllTestProtocol :: super.protocols

  override def assembles: List[Assemble] = {
    new TypedAllTestAssemble(classOf[D_Model1]) :: new TypedAllTestAssemble(classOf[D_Model2]) :: new TestAllEach ::
      super.assembles
  }

  lazy val assembleProfiler = NoAssembleProfiler //ValueAssembleProfiler2
}