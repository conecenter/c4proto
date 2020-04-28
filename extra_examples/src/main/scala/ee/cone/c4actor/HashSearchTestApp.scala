package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.EqProtocol.{D_ChangingNode, D_IntEq, D_StrStartsWith, D_TestObject, D_TestObject2}
import ee.cone.c4actor.HashSearch.{Request, Response}
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.TestProtocol.D_TestNode
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.request.CurrentTimeApp
import ee.cone.c4actor.hashsearch.base.HashSearchAssembleApp
import ee.cone.c4actor.hashsearch.condition.ConditionCheckWithCl
import ee.cone.c4actor.hashsearch.index.StaticHashSearchImpl.StaticFactoryImpl
import ee.cone.c4actor.hashsearch.index.dynamic.IndexNodeProtocol.{S_IndexByNode, S_IndexNode, S_IndexNodeSettings}
import ee.cone.c4actor.hashsearch.index.dynamic.{DynamicIndexAssemble, DynamicIndexModelsProvider, ProductWithId}
import ee.cone.c4actor.hashsearch.rangers.IndexType.{Default, IndexType}
import ee.cone.c4actor.hashsearch.rangers.{HashSearchRangerRegistryMix, RangerWithCl}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4di.{c4, c4app}
import ee.cone.c4proto.{GenLens, Id, protocol}

//  C4STATE_TOPIC_PREFIX=ee.cone.c4actor.HashSearchExtraTestApp sbt ~'c4actor-extra-examples/runMain ee.cone.c4actor.ServerMain'
@c4("HashSearchExtraTestApp") final class HashSearchExtraTestStart(
  execution: Execution,
  toUpdate: ToUpdate,
  contextFactory: ContextFactory,
  //rawWorldFactory: RichRawWorldFactory, /* progressObserverFactory: ProgressObserverFactory,*/
  //observer: Option[Observer],
  qAdapterRegistry: QAdapterRegistry,
  activateContext: ActivateContext,
  getCustomResponse: GetByPK[CustomResponse],
  getS_IndexNode: GetByPK[S_IndexNode],
  getS_IndexByNode: GetByPK[S_IndexByNode],
  txAdd: LTxAdd,
) extends Executable with LazyLogging {
  def run(): Unit = {
    import LEvent.update

    val world = for {
      i <- 1 to 10000
    } yield D_TestObject(i.toString, 239, i.toHexString)
    val recs = /*update(D_TestNode("1", "")) ++ */ update(S_Firstborn("test", "0" * OffsetHexSize())) ++ update(D_ChangingNode("test", "6")) ++ update(D_ChangingNode("test-safe", "45")) ++ world.flatMap(update)
    val updates: List[QProtocol.N_Update] = recs.map(rec => toUpdate.toUpdate(rec)).toList
    val nGlobal = contextFactory.updated(updates)
    val nGlobalActive = activateContext(nGlobal)
    val nGlobalAA = activateContext(nGlobalActive)

    //logger.info(s"${nGlobal.assembled}")
    println("0<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")
    //println( /*getD_TestObject: GetByPK[D_TestObject],*/getD_TestObject.ofA(nGlobal).values.toList)
    println("Should", List(17, 273))
    println("Answer", getCustomResponse.ofA(nGlobalAA).values.toList.map(_.list.size))
    println(getS_IndexNode.ofA(nGlobalAA).values)
    println(getS_IndexByNode.ofA(nGlobalAA).values.map(meh => meh.leafId -> meh.byStr))
    //Thread.sleep(3000)
    println("1>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")
    val newNGlobal: Context = txAdd.add(LEvent.update(D_TestObject("124", 239, "adb")) ++ LEvent.update(D_ChangingNode("test", "1")))(nGlobalAA)
    val newNGlobalA = activateContext(newNGlobal)
    val newNGlobalAA = activateContext(newNGlobalA)
    println("1<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")
    //println( /*getD_TestObject: GetByPK[D_TestObject],*/getD_TestObject.ofA(newNGlobal).values.toList)
    println("Should", List(17, 4369))
    println("Answer", /*getCustomResponse: GetByPK[CustomResponse],*/ getCustomResponse.ofA(newNGlobalAA).values.toList.map(_.list.size))
    println(/*getS_IndexByNode: GetByPK[S_IndexByNode],*/ getS_IndexByNode.ofA(newNGlobalAA).values.map(meh => meh.leafId -> meh.byStr))
    println("2>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")
    val newNGlobal2 = txAdd.add(LEvent.update(D_TestObject("124", 239, "adb")) ++ LEvent.update(D_ChangingNode("test", "")))(newNGlobalAA)
    Thread.sleep(10000)
    val newNGlobal2A = activateContext(newNGlobal2)
    val newNGlobal2AA = activateContext(newNGlobal2A)
    println("2<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")
    //println( /*getD_TestObject: GetByPK[D_TestObject],*/getD_TestObject.ofA(newNGlobal).values.toList)
    println("Should", List(17, 10000))
    println("Answer", /*getCustomResponse: GetByPK[CustomResponse],*/ getCustomResponse.ofA(newNGlobal2AA).values.toList.map(_.list.size))
    println(/*getS_IndexByNode: GetByPK[S_IndexByNode],*/ getS_IndexByNode.ofA(newNGlobal2AA).values.map(meh => meh.leafId -> meh.byStr))
    println("2<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")
    execution.complete()

  }
}

case class CustomResponse(srcId: SrcId, list: List[D_TestObject])

@assemble class CreateRequestBase(condition: List[Condition[D_TestObject]], changingCondition: String => Condition[D_TestObject]) {
  def createRequest(
    testId: SrcId,
    test: Each[D_TestNode]
  ): Values[(SrcId, Request[D_TestObject])] = for {
    cond <- condition
  } yield WithPK(Request(test.srcId + "_" + cond.toString.take(10), cond))

  def createRequestChanging(
    testId: SrcId,
    test: Each[D_ChangingNode]
  ): Values[(SrcId, Request[D_TestObject])] = {
    val cond = changingCondition(test.value)
    List(WithPK(Request(test.srcId + "_" + cond.toString.take(10), cond)))
  }

  def createRequestChanging2(
    testId: SrcId,
    tests: Values[D_ChangingNode]
  ): Values[(SrcId, Request[D_TestObject2])] =
    Nil

  def grabResponse(
    responseId: SrcId,
    tests: Values[D_TestNode],
    responses: Values[Response[D_TestObject]]
  ): Values[(SrcId, CustomResponse)] = {
    //println("Answer", responses.map(_.lines))
    (responseId -> CustomResponse(responseId, responses.flatMap(_.lines).toList)) :: Nil
  }

  /*def printAllInners(
    innerId: SrcId,
    inners: Values[InnerLeaf[D_TestObject]]
  ): Values[(SrcId, CustomResponse)] = {
    //println("Inner", inners)
    Nil
  }

  def printAllOuters(
    innerId: SrcId,
    inners: Values[OuterCondition[D_TestObject]]
  ): Values[(SrcId, CustomResponse)] = {
    //println("Outer", inners)
    Nil
  }*/
}

trait EqProtocolAppBase

@protocol("EqProtocolApp") object EqProtocol {

  @Id(0xaabc) case class D_ChangingNode(
    @Id(0xaabd) srcId: String,
    @Id(0xaabe) value: String
  )

  @Id(0x4567) case class D_IntEq(
    @Id(0xabcd) value: Int
  )

  @Id(0xaaaa) case class D_StrStartsWith(
    @Id(0xaaab) value: String
  )

  @GenLens
  @Id(0xaaad) case class D_TestObject(
    @Id(0xaaae) srcId: String,
    @Id(0xaaba) valueInt: Int,
    @Id(0xaabb) valueStr: String
  )

  @Id(0xaa01) case class D_TestObject2(
    @Id(0xaaae) srcId: String,
    @Id(0xaaba) valueInt: Int,
    @Id(0xaabb) valueStr: String
  )

}

case object StrStartsWithChecker extends ConditionCheckWithCl(classOf[D_StrStartsWith], classOf[String]) {
  def prepare: List[AbstractMetaAttr] => D_StrStartsWith => D_StrStartsWith = _ => by => by

  def check: D_StrStartsWith => String => Boolean = {
    case D_StrStartsWith(v) => _.startsWith(v)
  }

  def defaultBy: Option[D_StrStartsWith => Boolean] = None
}

case object StrStartsWithRanger extends RangerWithCl(classOf[D_StrStartsWith], classOf[String]) {
  def ranges: D_StrStartsWith => (String => List[D_StrStartsWith], PartialFunction[Product, List[D_StrStartsWith]]) = {
    case D_StrStartsWith("") => (
      value => (
        (for {
          i <- 1 to 5
        } yield D_StrStartsWith(value.take(i))
          ).toList :+ D_StrStartsWith("")).distinct, {
      case D_StrStartsWith(v) => D_StrStartsWith(v.take(5)) :: Nil
    })
    case a => FailWith(s"Unsupported option $a")
  }

  def prepareRequest: D_StrStartsWith => D_StrStartsWith = in => in.copy(value = in.value.take(5))
}

object DefaultStrStartsWithInitializer extends DefaultModelInitializer[D_StrStartsWith](classOf[D_StrStartsWith], _.copy(value = ""))

case object IntEqCheck extends ConditionCheckWithCl[D_IntEq, Int](classOf[D_IntEq], classOf[Int]) {
  def prepare: List[AbstractMetaAttr] => D_IntEq => D_IntEq = _ => identity[D_IntEq]

  def check: D_IntEq => Int => Boolean = by => value => true

  def defaultBy: Option[D_IntEq => Boolean] = None
}

case class IntEqRanger() extends RangerWithCl[D_IntEq, Int](classOf[D_IntEq], classOf[Int]) {
  def ranges: D_IntEq => (Int => List[D_IntEq], PartialFunction[Product, List[D_IntEq]]) = {
    case D_IntEq(0) => (
      value => List(D_IntEq(value), D_IntEq(0)).distinct, {
      case p@D_IntEq(v) => List(p)
    }
    )
  }

  def prepareRequest: D_IntEq => D_IntEq = identity
}

object DefaultInitializer extends DefaultModelInitializer[D_IntEq](classOf[D_IntEq], _.copy(value = 0))

@fieldAccess object D_TestObjectLensesBase {
  val valueStr: ProdGetter[D_TestObject, String] = ProdGetter.of(_.valueStr)
  val valueInt: ProdGetter[D_TestObject, Int] = ProdGetter.of(_.valueInt)
}

trait TestCondition extends SerializationUtilsApp {
  def changingCondition: String => Condition[D_TestObject] = value => {
    IntersectCondition(
      IntersectCondition(
        ProdConditionImpl(NameMetaAttr("testLensInt") :: Nil, D_IntEq(0))(IntEqCheck.check(D_IntEq(0)), _.valueInt),
        AnyCondition()
      ),
      ProdConditionImpl(NameMetaAttr("testLensStr") :: Nil, D_StrStartsWith(value))(StrStartsWithChecker.check(D_StrStartsWith(value)), _.valueStr)
    )
  }

  def condition1: Condition[D_TestObject] = {
    UnionCondition(
      ProdConditionImpl(NameMetaAttr("testLensInt") :: Nil, D_IntEq(239))(IntEqCheck.check(D_IntEq(239)), _.valueInt),
      ProdConditionImpl(NameMetaAttr("testLensInt") :: Nil, D_IntEq(666))(IntEqCheck.check(D_IntEq(666)), _.valueInt)
    )
  }

  def condition2: Condition[D_TestObject] = {
    IntersectCondition(
      IntersectCondition(
        ProdConditionImpl(NameMetaAttr("testLensInt") :: Nil, D_IntEq(239))(IntEqCheck.check(D_IntEq(239)), _.valueInt),
        AnyCondition()
        //ProdConditionImpl(NameMetaAttr("testLens") :: Nil, D_IntEq(666))(IntEqCheck.check(D_IntEq(666)), _.value)
      ),
      AnyCondition()
    )
  }

  def condition3 = IntersectCondition(condition1, condition2)

  def conditions: List[Condition[D_TestObject]] = condition1 /*:: condition2*//*:: condition3*/ :: Nil

  def idGenUtil: IdGenUtil
  def indexUtil: IndexUtil

  def factory = new StaticFactoryImpl(new ModelConditionFactoryImpl, serializer, idGenUtil, indexUtil)

  def joiners: List[Assemble] = Nil

  /*factory.index(classOf[D_TestObject])
     .add[D_IntEq, Int](lensInt, D_IntEq(0))(IntEqRanger())
     .add[D_StrStartsWith, String](lensStr, D_StrStartsWith(""))(StrStartsWithRanger)
     .assemble*/

  def lensInt: ProdGetter[D_TestObject, Int] = D_TestObjectLenses.valueInt

  def lensStr: ProdGetter[D_TestObject, String] = D_TestObjectLenses.valueStr
}

@c4app trait HashSearchExtraTestAppBase extends TestVMRichDataApp
  //with ServerApp
  //with EnvConfigApp
  with VMExecutionApp
  //with ParallelObserversApp
  //with RemoteRawSnapshotApp
  with ExecutableApp
  with ModelAccessFactoryCompApp
  with TestCondition
  with HashSearchAssembleApp
  with SerializationUtilsMix
  with DynamicIndexAssemble
  with LensRegistryMix
  with HashSearchRangerRegistryMix
  with CurrentTimeApp
  with WithMurMur3HashGenApp
  with ProdLensesApp
  with EqProtocolApp
  with TestProtocolApp
  with AnyOrigProtocolApp
  with ActivateContextApp {
  // println(TestProtocolM.adapters.map(a => a.categories))

  override def getterList: List[ProdGetter[_, _]] = lensInt :: lensStr :: super.getterList

  override def hashSearchRangers: List[RangerWithCl[_ <: Product, _]] = StrStartsWithRanger :: IntEqRanger() :: super.hashSearchRangers

  //override def rawQSender: RawQSender = NoRawQSender

  //override def dynamicIndexAssembleDebugMode: Boolean = false

  override def assembles: List[Assemble] = {
    println((new CreateRequest(conditions, changingCondition) :: /*joiners*/
      super.assembles).mkString("\n")
    )
    new CreateRequest(conditions, changingCondition) :: /*joiners*/
      super.assembles
  }

  lazy val assembleProfiler = NoAssembleProfiler //ConsoleAssembleProfiler //ValueAssembleProfiler2

  def dynamicIndexRefreshRateSeconds: Long = 1L

  //override def dynamicIndexNodeDefaultSetting: S_IndexNodeSettings = S_IndexNodeSettings("", false, Some(100L))
}

@c4("HashSearchExtraTestApp") final class TestDynamicIndexModelsProvider extends DynamicIndexModelsProvider(ProductWithId(classOf[D_TestObject], 1) :: Nil)

/*
object ValueAssembleProfiler2 extends AssembleProfiler {
  def get(ruleName: String): String => Int => Unit = startAction => {
    val startTime = System.currentTimeMillis
    finalCount => {
      if (true) {
        val period = System.currentTimeMillis - startTime
        if (period > 0)
          println(s"${Console.WHITE_B}${Console.BLACK}rule ${trimStr(ruleName, 50)}|${trimStr(startAction, 10)} $finalCount|$period ms${Console.RESET}")
      }
    }
  }

  def trimStr(str: String, limit: Int): String = {
    val length = str.length
    if (length >= limit)
      str.take(limit)
    else
      str + List.range(0, limit - length).map(_ => "").mkString(" ")
    str
  }

  override def getOpt(ruleName: String, in: immutable.Seq[AssembledKey], out: AssembledKey): Option[String => Int => Unit] = Some {
    if (ruleName != "IndexModelToHeapBy")
      get(ruleName)
    else {
      get(ruleName + in.mkString("|"))
      //get("")
    }
  }
}
*/