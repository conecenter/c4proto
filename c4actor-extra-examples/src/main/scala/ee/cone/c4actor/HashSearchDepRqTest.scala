package ee.cone.c4actor

import ee.cone.c4actor.dep.request.HashSearchDepRequestProtocol.{By, DepCondition, HashSearchDepRequest}
import ee.cone.c4actor.dep.request.{ByMaker, HashSearchDepRequestHandler, LeafInfoHolder, LeafRegistryImpl}

case class Model(value: String)

case class StrEq(value: String)

case object StrEqCheck extends ConditionCheck[StrEq, String] {
  def prepare: List[MetaAttr] ⇒ StrEq ⇒ StrEq = _ ⇒ identity[StrEq]

  def check: StrEq ⇒ String ⇒ Boolean = by ⇒ value ⇒ value == by.value
}

case object StrEqMaker extends ByMaker[StrEq] {

  def make: String => StrEq = input ⇒ StrEq(input)

  def byCl: Class[StrEq] = classOf[StrEq]
}

object HashSearchDepRqTest {
  def main(args: Array[String]): Unit = {
    val lens = ProdLens.ofSet[Model, String](_.value, l => _.copy(value = l), "ModelLens")
    val leafRegistry = LeafRegistryImpl(List(LeafInfoHolder(lens, Nil, StrEqCheck, classOf[Model], classOf[StrEq], classOf[String])), List(StrEqMaker), List(classOf[Model]))
    val handler = HashSearchDepRequestHandler(leafRegistry, new ModelConditionFactoryImpl)
    val leaf = DepCondition(classOf[Model].getName, "leaf", None, None, "ModelLens", Option(By(classOf[StrEq].getName, "123")))
    val any = DepCondition(classOf[Model].getName, "any", None, None, "", None)
    val conjunction = DepCondition(classOf[Model].getName, "union", Option(leaf), Option(any), "", None)
    val request = HashSearchDepRequest(classOf[Model].getName, conjunction)
    println(handler.handle(request, "123").toString)
  }
}
