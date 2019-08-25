package ee.cone.c4actor.hashsearch.base

import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.{Condition, _}
import ee.cone.c4actor.dep.request.HashSearchDepRequestProtocol.{N_By, N_DepCondition, N_HashSearchDepRequest}
import ee.cone.c4proto.ToByteString

trait HashSearchDepRequestFactoryApp {
  def hashSearchDepRequestFactory: HashSearchDepRequestFactory[_]
}

trait HashSearchDepRequestFactoryMix extends HashSearchDepRequestFactoryApp{
  def qAdapterRegistry: QAdapterRegistry

  def hashSearchDepRequestFactory: HashSearchDepRequestFactory[_] = HashSearchDepRequestFactoryImpl(S_Firstborn.getClass, qAdapterRegistry)
}

trait HashSearchDepRequestFactory[Model] {
  def intersect: (N_DepCondition, N_DepCondition) ⇒ N_DepCondition

  def union: (N_DepCondition, N_DepCondition) ⇒ N_DepCondition

  def any: N_DepCondition

  def leaf[By <: Product](lensName: NameMetaAttr, by: By): N_DepCondition

  def request: N_DepCondition ⇒ N_HashSearchDepRequest

  def conditionToHashSearchRequest: Condition[Model] ⇒ N_HashSearchDepRequest

  def conditionToDepCond: Condition[Model] ⇒ N_DepCondition

  def ofWithCl[OtherModel](otherModel: Class[OtherModel]): HashSearchDepRequestFactory[OtherModel]
}

case class HashSearchDepRequestFactoryImpl[Model](modelCl: Class[Model], qAdapterRegistry: QAdapterRegistry) extends HashSearchDepRequestFactory[Model] {
  def intersect: (N_DepCondition, N_DepCondition) => N_DepCondition =
    (a, b) ⇒ N_DepCondition(modelCl.getName, "intersect", Option(a), Option(b), "", None)

  def union: (N_DepCondition, N_DepCondition) => N_DepCondition =
    (a, b) ⇒ N_DepCondition(modelCl.getName, "union", Option(a), Option(b), "", None)

  def any: N_DepCondition =
    N_DepCondition(modelCl.getName, "any", None, None, "", None)

  def leaf[By <: Product](lensName: NameMetaAttr, byInst: By): N_DepCondition = {
    val clName = byInst.getClass.getName
    val adapter = qAdapterRegistry.byName(clName)
    N_DepCondition(modelCl.getName, "leaf", None, None, lensName.value, Option(N_By(clName, ToByteString(adapter.encode(byInst)))))
  }

  def request: N_DepCondition => N_HashSearchDepRequest =
    cond ⇒ N_HashSearchDepRequest(modelCl.getName, Option(cond))

  def conditionToHashSearchRequest: Condition[Model] ⇒ N_HashSearchDepRequest = cond ⇒
    request(conditionToDepCond(cond))

  def conditionToDepCond: Condition[Model] ⇒ N_DepCondition = {
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

  def ofWithCl[OtherModel](otherModel: Class[OtherModel]): HashSearchDepRequestFactory[OtherModel] = HashSearchDepRequestFactoryImpl(otherModel, qAdapterRegistry)
}
