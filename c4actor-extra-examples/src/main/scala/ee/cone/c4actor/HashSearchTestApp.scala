package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.EqProtocol.{ChangingNode, IntEq, StrStartsWith, TestObject}
import ee.cone.c4actor.HashSearch.{Request, Response}
import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.TestProtocol.TestNode
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.hashsearch.base.HashSearchAssembleApp
import ee.cone.c4actor.hashsearch.condition.{ConditionCheckWithCl, SerializationUtilsApp, SerializationUtilsMix}
import ee.cone.c4actor.hashsearch.index.StaticHashSearchImpl.StaticFactoryImpl
import ee.cone.c4actor.hashsearch.index.dynamic.{DynamicIndexAssemble, IndexByNodeRich, ProductWithId}
import ee.cone.c4actor.hashsearch.index.dynamic.IndexNodeProtocol.{IndexByNode, IndexByNodeStats, IndexNode}
import ee.cone.c4actor.hashsearch.rangers.{HashSearchRangerRegistryMix, RangerWithCl}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4proto.{Id, Protocol, protocol}

//  C4STATE_TOPIC_PREFIX=ee.cone.c4actor.HashSearchExtraTestApp sbt ~'c4actor-extra-examples/runMain ee.cone.c4actor.ServerMain'
class HashSearchExtraTestStart(
  execution: Execution,
  toUpdate: ToUpdate,
  contextFactory: ContextFactory,
  rawWorldFactory: RawWorldFactory, /* progressObserverFactory: ProgressObserverFactory,*/
  observer: Option[Observer],
  qAdapterRegistry: QAdapterRegistry
) extends Executable with LazyLogging {
  def run(): Unit = {
    import LEvent.update

    val world = for {
      i ← 1 to 10000
    } yield TestObject(i.toString, 239, i.toString.take(5))
    val recs = /*update(TestNode("1", "")) ++ */ update(Firstborn("test")) ++ update(ChangingNode("test", "")) ++ update(ChangingNode("test-safe", "")) ++ world.flatMap(update)
    val updates: List[QProtocol.Update] = recs.map(rec ⇒ toUpdate.toUpdate(rec)).toList
    val context: Context = contextFactory.create()
    val nGlobal: Context = ReadModelAddKey.of(context)(updates)(context)
    val nGlobalActive = ActivateContext(nGlobal)
    val nGlobalAA = ActivateContext(nGlobalActive)

    //logger.info(s"${nGlobal.assembled}")
    println("0<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")
    //println(ByPK(classOf[TestObject]).of(nGlobal).values.toList)
    println("Answer", ByPK(classOf[CustomResponse]).of(nGlobalAA).values.toList.map(_.list.size))
    println(ByPK(classOf[IndexNode]).of(nGlobalAA).values)
    println(ByPK(classOf[IndexByNode]).of(nGlobalAA).values.map(meh ⇒ meh.srcId → meh.byInstance.get).map(meh ⇒ meh._1 → AnyAdapter.decode(qAdapterRegistry)(meh._2)))
    println(ByPK(classOf[IndexByNodeStats]).of(nGlobalAA).values)
    Thread.sleep(3000)
    println("1>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")
    val newNGlobal: Context = TxAdd(LEvent.update(TestObject("124", 239, "adb")) ++ LEvent.update(ChangingNode("test", "1")))(nGlobalAA)
    val newNGlobalA = ActivateContext(newNGlobal)
    val newNGlobalAA = ActivateContext(newNGlobalA)
    println("1<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")
    //println(ByPK(classOf[TestObject]).of(newNGlobal).values.toList)
    println("Answer", ByPK(classOf[CustomResponse]).of(newNGlobalAA).values.toList.map(_.list.size))
    println(ByPK(classOf[IndexByNode]).of(newNGlobalAA).values.map(meh ⇒ meh.srcId → meh.byInstance.get).map(meh ⇒ meh._1 → AnyAdapter.decode(qAdapterRegistry)(meh._2)))
    println(ByPK(classOf[IndexByNodeStats]).of(newNGlobalAA).values)
    println("2>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")
    val newNGlobal2 = TxAdd(LEvent.update(TestObject("124", 239, "adb")) ++ LEvent.update(ChangingNode("test", "")))(newNGlobalAA)
    Thread.sleep(10000)
    val newNGlobal2A = ActivateContext(newNGlobal2)
    val newNGlobal2AA = ActivateContext(newNGlobal2A)
    println("2<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")
    //println(ByPK(classOf[TestObject]).of(newNGlobal).values.toList)
    println("Answer", ByPK(classOf[CustomResponse]).of(newNGlobal2AA).values.toList.map(_.list.size))
    println(ByPK(classOf[IndexByNode]).of(newNGlobal2AA).values.map(meh ⇒ meh.srcId → meh.byInstance.get).map(meh ⇒ meh._1 → AnyAdapter.decode(qAdapterRegistry)(meh._2)))
    println(ByPK(classOf[IndexByNodeStats]).of(newNGlobal2AA).values)
    execution.complete()

  }
}

case class CustomResponse(srcId: SrcId, list: List[TestObject])

@assemble class CreateRequest(condition: List[Condition[TestObject]], changingCondition: String ⇒ Condition[TestObject]) extends Assemble {
  def createRequest(
    testId: SrcId,
    tests: Values[TestNode]
  ): Values[(SrcId, Request[TestObject])] =
    tests.flatMap(test ⇒ condition.map(cond ⇒ WithPK(Request(test.srcId + "_" + cond.toString.take(10), cond))))

  def createRequestChanging(
    testId: SrcId,
    tests: Values[ChangingNode]
  ): Values[(SrcId, Request[TestObject])] =
    for {
      test ← tests
    } yield {
      val cond = changingCondition(test.value)
      WithPK(Request(test.srcId + "_" + cond.toString.take(10), cond))
    }

  def grabResponse(
    responseId: SrcId,
    tests: Values[TestNode],
    responses: Values[Response[TestObject]]
  ): Values[(SrcId, CustomResponse)] = {
    //println("Answer", responses.map(_.lines))
    (responseId → CustomResponse(responseId, responses.flatMap(_.lines).toList)) :: Nil
  }

  /*def printAllInners(
    innerId: SrcId,
    inners: Values[InnerLeaf[TestObject]]
  ): Values[(SrcId, CustomResponse)] = {
    //println("Inner", inners)
    Nil
  }

  def printAllOuters(
    innerId: SrcId,
    inners: Values[OuterCondition[TestObject]]
  ): Values[(SrcId, CustomResponse)] = {
    //println("Outer", inners)
    Nil
  }*/
}


@protocol object EqProtocol extends Protocol {

  @Id(0xaabc) case class ChangingNode(
    @Id(0xaabd) srcId: String,
    @Id(0xaabe) value: String
  )

  @Id(0x4567) case class IntEq(
    @Id(0xabcd) value: Int
  )

  @Id(0xaaaa) case class StrStartsWith(
    @Id(0xaaab) value: String
  )

  @Id(0xaaad) case class TestObject(
    @Id(0xaaae) srcId: String,
    @Id(0xaaba) valueInt: Int,
    @Id(0xaabb) valueStr: String
  )

}

case object StrStartsWithChecker extends ConditionCheckWithCl(classOf[StrStartsWith], classOf[String]) {
  def prepare: List[MetaAttr] => StrStartsWith => StrStartsWith = _ ⇒ by ⇒ by

  def check: StrStartsWith => String => Boolean = {
    case StrStartsWith(v) ⇒ _.startsWith(v)
  }
}

case object StrStartsWithRanger extends RangerWithCl(classOf[StrStartsWith], classOf[String]) {
  def ranges: StrStartsWith => (String => List[StrStartsWith], PartialFunction[Product, List[StrStartsWith]]) = {
    case StrStartsWith("") ⇒ (
      value ⇒ (
        for {
          i ← 1 to 5
        } yield StrStartsWith(value.take(i))
        ).toList :+ StrStartsWith(""), {
      case StrStartsWith(v) ⇒ StrStartsWith(v.take(5)) :: Nil
    })
  }
}

object DefaultStrStartsWith extends DefaultModelFactory[StrStartsWith](classOf[StrStartsWith], _ ⇒ StrStartsWith(""))

case object IntEqCheck extends ConditionCheckWithCl[IntEq, Int](classOf[IntEq], classOf[Int]) {
  def prepare: List[MetaAttr] ⇒ IntEq ⇒ IntEq = _ ⇒ identity[IntEq]

  def check: IntEq ⇒ Int ⇒ Boolean = by ⇒ value ⇒ true
}

case class IntEqRanger() extends RangerWithCl[IntEq, Int](classOf[IntEq], classOf[Int]) {
  def ranges: IntEq ⇒ (Int ⇒ List[IntEq], PartialFunction[Product, List[IntEq]]) = {
    case IntEq(0) ⇒ (
      value ⇒ List(IntEq(value), IntEq(0)), {
      case p@IntEq(v) ⇒ List(p)
    }
    )
  }
}

object DefaultIntEq extends DefaultModelFactory[IntEq](classOf[IntEq], id ⇒ IntEq(0))

trait TestCondition extends SerializationUtilsApp {
  def changingCondition: String ⇒ Condition[TestObject] = value ⇒ {
    IntersectCondition(
      IntersectCondition(
        ProdConditionImpl(NameMetaAttr("testLensInt") :: Nil, IntEq(0))(IntEqCheck.check(IntEq(0)), _.valueInt),
        AnyCondition()
      ),
      ProdConditionImpl(NameMetaAttr("testLensStr") :: Nil, StrStartsWith(value))(StrStartsWithChecker.check(StrStartsWith(value)), _.valueStr)
    )
  }

  def condition1: Condition[TestObject] = {
    UnionCondition(
      ProdConditionImpl(NameMetaAttr("testLensInt") :: Nil, IntEq(239))(IntEqCheck.check(IntEq(239)), _.valueInt),
      ProdConditionImpl(NameMetaAttr("testLensInt") :: Nil, IntEq(666))(IntEqCheck.check(IntEq(666)), _.valueInt)
    )
  }

  def condition2: Condition[TestObject] = {
    IntersectCondition(
      IntersectCondition(
        ProdConditionImpl(NameMetaAttr("testLensInt") :: Nil, IntEq(239))(IntEqCheck.check(IntEq(239)), _.valueInt),
        AnyCondition()
        //ProdConditionImpl(NameMetaAttr("testLens") :: Nil, IntEq(666))(IntEqCheck.check(IntEq(666)), _.value)
      ),
      AnyCondition()
    )
  }

  def condition3 = IntersectCondition(condition1, condition2)

  def conditions: List[Condition[TestObject]] = condition1 /*:: condition2*//*:: condition3*/ :: Nil

  def factory = new StaticFactoryImpl(new ModelConditionFactoryImpl, serializer)

  def joiners: List[Assemble] = factory.index(classOf[TestObject])
    .add[IntEq, Int](lensInt, IntEq(0))(IntEqRanger())
    .add[StrStartsWith, String](lensStr, StrStartsWith(""))(StrStartsWithRanger)
    .assemble

  def lensInt: ProdLens[TestObject, Int] = ProdLens.ofSet[TestObject, Int](_.valueInt, value ⇒ _.copy(valueInt = value), "testLensInt")

  def lensStr: ProdLens[TestObject, String] = ProdLens.ofSet[TestObject, String](_.valueStr, value ⇒ _.copy(valueStr = value), "testLensStr")
}

class HashSearchExtraTestApp extends RichDataApp
  with ServerApp
  with EnvConfigApp with VMExecutionApp
  with ParallelObserversApp
  with FileRawSnapshotApp
  with TreeIndexValueMergerFactoryApp
  with ExecutableApp
  with ToStartApp
  with MortalFactoryApp
  with ModelAccessFactoryApp
  with TestCondition
  with HashSearchAssembleApp
  with SerializationUtilsMix
  with CurrentTimeAssembleMix
  with DynamicIndexAssemble
  with LensRegistryMix
  with HashSearchRangerRegistryMix
  with DefaultModelFactoriesApp
  with ProdLensesApp {


  override def lensList: List[ProdLens[_, _]] = lensInt :: lensStr :: super.lensList

  override def defaultModelFactories: List[DefaultModelFactory[_]] = DefaultIntEq :: DefaultStrStartsWith :: super.defaultModelFactories

  override def hashSearchRangers: List[RangerWithCl[_ <: Product, _]] = StrStartsWithRanger :: IntEqRanger() :: super.hashSearchRangers

  override def rawQSender: RawQSender = NoRawQSender

  override def parallelAssembleOn: Boolean = true

  override def dynamicIndexAssembleDebugMode: Boolean = false

  override def toStart: List[Executable] = new HashSearchExtraTestStart(execution, toUpdate, contextFactory, rawWorldFactory, txObserver, qAdapterRegistry) :: super.toStart


  override def hashSearchModels: List[Class[_ <: Product]] = classOf[TestObject] :: super.hashSearchModels


  override def protocols: List[Protocol] = AnyProtocol :: EqProtocol :: TestProtocol :: super.protocols

  override def assembles: List[Assemble] = {
    println((new CreateRequest(conditions, changingCondition) :: /*joiners*/
      super.assembles).mkString("\n")
    )
    new CreateRequest(conditions, changingCondition) :: /*joiners*/
      super.assembles
  }

  lazy val assembleProfiler: AssembleProfiler = ValueAssembleProfiler2

  override def dynIndexModels: List[ProductWithId[_ <: Product]] = ProductWithId(classOf[TestObject], 1) :: super.dynIndexModels

  def dynamicIndexRefreshRateSeconds: Long = 1L
}

object ValueAssembleProfiler2 extends AssembleProfiler {
  def get(ruleName: String): String ⇒ Int ⇒ Unit = startAction ⇒ {
    val startTime = System.currentTimeMillis
    finalCount ⇒ {
      val period = System.currentTimeMillis - startTime
      if (period > 10)
        println(s"assembling rule $ruleName $startAction $finalCount items in $period ms")
    }
  }
}