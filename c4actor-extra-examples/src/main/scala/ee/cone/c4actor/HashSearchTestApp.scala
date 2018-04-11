package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.EqProtocol.IntEq
import ee.cone.c4actor.HashSearch.{Request, Response}
import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.TestProtocol.{TestNode, ValueNode}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.hashsearch.HashSearchAssembleApp
import ee.cone.c4actor.hashsearch.HashSearchImpl2.StaticFactoryImpl
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4proto.{Id, Protocol, protocol}

//  C4STATE_TOPIC_PREFIX=ee.cone.c4actor.HashSearchTestAppp sbt ~'c4actor-extra-examples/runMain ee.cone.c4actor.ServerMain'
class HashSearchTesttStart(
  execution: Execution, toUpdate: ToUpdate, contextFactory: ContextFactory
) extends Executable with LazyLogging {
  def run() = {
    import LEvent.update

    val recs = update(TestNode("1", "")) ++ update(ValueNode("123", 239)) ++ update(ValueNode("124", 666))
    val updates: List[QProtocol.Update] = recs.map(rec ⇒ toUpdate.toUpdate(rec)).toList
    val context: Context = contextFactory.create()
    val nGlobal: Context = ReadModelAddKey.of(context)(updates)(context)

    //logger.info(s"${nGlobal.assembled}")
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println(ByPK(classOf[Response[ValueNode]]).of(nGlobal).values.toList)
    execution.complete()

  }
}

@assemble class CreateRequest(condition: Condition[ValueNode]) extends Assemble {
  def createRequest(
    testId: SrcId,
    tests: Values[TestNode]
  ): Values[(SrcId, Request[ValueNode])] =
    for {
      test ← tests
    } yield WithPK(Request(testId, condition))

  def grabResponse(
    responseId: SrcId,
    tests: Values[TestNode],
    responses: Values[Response[ValueNode]]
  ): Values[(SrcId, Firstborn)] = {
    println("Answer", responses.map(_.lines))
    Nil
  }
}


@protocol object EqProtocol extends Protocol {

  @Id(0x4567) case class IntEq(
    @Id(0xabcd) value: Int
  )

}

case object IntEqCheck extends ConditionCheck[IntEq, Int] {
  def prepare: List[MetaAttr] ⇒ IntEq ⇒ IntEq = _ ⇒ identity[IntEq]

  def check: IntEq ⇒ Int ⇒ Boolean = by ⇒ value ⇒ value == by.value
}

case object IntEqRanger extends Ranger[IntEq, Int] {
  def ranges: IntEq ⇒ (Int ⇒ List[IntEq], PartialFunction[Product, List[IntEq]]) = {
    case IntEq(0) ⇒ (
      value ⇒ List(IntEq(value)), {
      case p@IntEq(v) ⇒ List(p)
    }
    )
  }
}

trait TestCondition {
  def condition: Condition[ValueNode] = {
    UnionCondition(
      ProdConditionImpl(NameMetaAttr("testLens") :: Nil, IntEq(239))(IntEqCheck.check(IntEq(239)), _.value),
      ProdConditionImpl(NameMetaAttr("testLens") :: Nil, IntEq(666))(IntEqCheck.check(IntEq(666)), _.value)
    )
  }

  def factory = new StaticFactoryImpl(new ModelConditionFactoryImpl)

  def joiners: List[Assemble] = factory.index(classOf[ValueNode]).add(lens, IntEq(0))(IntEqRanger).assemble

  def lens: ProdLens[ValueNode, Int] = ProdLens.ofSet[ValueNode, Int](_.value, value ⇒ _.copy(value = value), "testLens")
}

class HashSearchTestAppp extends RichDataApp
  with ExecutableApp
  with VMExecutionApp
  with TreeIndexValueMergerFactoryApp
  with SimpleAssembleProfilerApp
  with ToStartApp
  with MortalFactoryApp
  with ModelAccessFactoryApp
  with TestCondition
  with HashSearchAssembleApp {

  override def toStart: List[Executable] = new HashSearchTesttStart(execution, toUpdate, contextFactory) :: super.toStart


  override def hashSearchModels: List[Class[_ <: Product]] = classOf[ValueNode] :: super.hashSearchModels


  override def protocols: List[Protocol] = EqProtocol :: TestProtocol :: super.protocols

  override def assembles: List[Assemble] = {
    println(super.assembles.mkString("\n"))
    new CreateRequest(condition) :: joiners :::
      super.assembles
  }
}