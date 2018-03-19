package ee.cone.c4actor.dep.request

import ee.cone.c4actor.{NameMetaAttr, ProdLens}
import ee.cone.c4actor.dep.request.HashSearchDepRequestProtocol.{By, DepCondition, HashSearchDepRequest}

trait HashSearchDepRequestFactory[Model] {
  def intersect: (DepCondition, DepCondition) ⇒ DepCondition

  def union: (DepCondition, DepCondition) ⇒ DepCondition

  def any: DepCondition

  def leaf[By <: Product, Field](lens: ProdLens[Model, Field], by: By): DepCondition

  def request: DepCondition ⇒ HashSearchDepRequest
}

trait ByToStrRegistry {
  def getStr[By](by: By): String
}

trait BySerializer[By] {
  def byCl: Class[By]

  def serialize[By2]: By2 ⇒ String
}

case class ByToStrRegistryImpl(byStrs: List[BySerializer[_]]) extends ByToStrRegistry {
  lazy val byStrMap: Map[Class[_], BySerializer[_]] = byStrs.map(ser ⇒ ser.byCl → ser).toMap[Class[_], BySerializer[_]]

  def getStr[By](by: By): String = byStrMap(by.getClass).serialize(by)
}

case class HashSearchDepRequestFactoryImpl[Model](modelCl: Class[Model], byToStrReg: ByToStrRegistry) extends HashSearchDepRequestFactory[Model] {
  def intersect: (DepCondition, DepCondition) => DepCondition =
    (a, b) ⇒ DepCondition(modelCl.getName, "intersect", Option(a), Option(b), "", None)

  def union: (DepCondition, DepCondition) => DepCondition =
    (a, b) ⇒ DepCondition(modelCl.getName, "union", Option(a), Option(b), "", None)

  def any: DepCondition =
    DepCondition(modelCl.getName, "any", None, None, "", None)

  def leaf[By <: Product, Field](lens: ProdLens[Model, Field], byInst: By): DepCondition =
    DepCondition(modelCl.getName, "leaf", None, None, lens.metaList.collect { case a: NameMetaAttr ⇒ a.value }.head, Option(By(byInst.getClass.getName, byToStrReg.getStr(byInst))))

  def request: DepCondition => HashSearchDepRequest =
    cond ⇒ HashSearchDepRequest(modelCl.getName, cond)
}
