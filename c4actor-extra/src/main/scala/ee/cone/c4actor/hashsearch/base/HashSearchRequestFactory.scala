package ee.cone.c4actor.hashsearch.base

import ee.cone.c4actor._
import ee.cone.c4actor.dep.request.HashSearchDepRequestProtocol.{By, DepCondition, HashSearchDepRequest}
import ee.cone.c4proto.ToByteString

trait HashSearchDepRequestFactory[Model] {
  def intersect: (DepCondition, DepCondition) ⇒ DepCondition

  def union: (DepCondition, DepCondition) ⇒ DepCondition

  def any: DepCondition

  def leaf[By <: Product](lensName: NameMetaAttr, by: By): DepCondition

  def request: DepCondition ⇒ HashSearchDepRequest

  def conditionToHashSearchRequest: Condition[Model] ⇒ HashSearchDepRequest

  def conditionToDepCond: Condition[Model] ⇒ DepCondition
}

case class HashSearchDepRequestFactoryImpl[Model](modelCl: Class[Model], qAdapterRegistry: QAdapterRegistry) extends HashSearchDepRequestFactory[Model] {
  def intersect: (DepCondition, DepCondition) => DepCondition =
    (a, b) ⇒ DepCondition(modelCl.getName, "intersect", Option(a), Option(b), "", None)

  def union: (DepCondition, DepCondition) => DepCondition =
    (a, b) ⇒ DepCondition(modelCl.getName, "union", Option(a), Option(b), "", None)

  def any: DepCondition =
    DepCondition(modelCl.getName, "any", None, None, "", None)

  def leaf[By <: Product](lensName: NameMetaAttr, byInst: By): DepCondition = {
    val clName = byInst.getClass.getName
    val adapter = qAdapterRegistry.byName(clName)
    DepCondition(modelCl.getName, "leaf", None, None, lensName.value, Option(By(clName, ToByteString(adapter.encode(byInst)))))
  }

  def request: DepCondition => HashSearchDepRequest =
    cond ⇒ HashSearchDepRequest(modelCl.getName, Option(cond))

  def conditionToHashSearchRequest: Condition[Model] ⇒ HashSearchDepRequest = cond ⇒
    request(conditionToDepCond(cond))

  def conditionToDepCond: Condition[Model] ⇒ DepCondition = {
    case IntersectCondition(left, right) ⇒
      val leftDep = conditionToDepCond(left)
      val rightDep = conditionToDepCond(right)
      intersect(leftDep, rightDep)
    case UnionCondition(left, right) ⇒
      val leftDep = conditionToDepCond(left)
      val rightDep = conditionToDepCond(right)
      union(leftDep, rightDep)
    case AnyCondition() ⇒ any
    case ProdConditionImpl(metaList, by) ⇒ leaf(metaList.collectFirst { case a: NameMetaAttr ⇒ a }.get, by)
    case cant ⇒ FailWith.apply(s"No such condition node $cant")
  }
}
