package ee.cone.c4actor.hashsearch.base

import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.{Condition, _}
import ee.cone.c4actor.dep.request.HashSearchDepRequestProtocol._
import ee.cone.c4proto.ToByteString

trait HashSearchDepRequestFactoryApp {
  def hashSearchDepRequestFactory: HashSearchDepRequestFactory[_]
}

trait HashSearchDepRequestFactoryMix extends HashSearchDepRequestFactoryApp{
  def qAdapterRegistry: QAdapterRegistry

  def hashSearchDepRequestFactory: HashSearchDepRequestFactory[_] = HashSearchDepRequestFactoryImpl(S_Firstborn.getClass, qAdapterRegistry)
}

trait HashSearchDepRequestFactory[Model] {
  def intersect: (ProtoDepCondition, ProtoDepCondition) ⇒ ProtoDepCondition

  def union: (ProtoDepCondition, ProtoDepCondition) ⇒ ProtoDepCondition

  def any: ProtoDepCondition

  def leaf[By <: Product](lensName: NameMetaAttr, by: By): ProtoDepCondition

  def request: ProtoDepCondition ⇒ N_HashSearchDepRequest

  def conditionToHashSearchRequest: Condition[Model] ⇒ N_HashSearchDepRequest

  def conditionToDepCond: Condition[Model] ⇒ ProtoDepCondition

  def ofWithCl[OtherModel](otherModel: Class[OtherModel]): HashSearchDepRequestFactory[OtherModel]
}

case class HashSearchDepRequestFactoryImpl[Model](modelCl: Class[Model], qAdapterRegistry: QAdapterRegistry) extends HashSearchDepRequestFactory[Model] {
  def intersect: (ProtoDepCondition, ProtoDepCondition) => ProtoDepCondition =
    (a, b) ⇒ N_DepConditionIntersect(Option(a), Option(b))

  def union: (ProtoDepCondition, ProtoDepCondition) => ProtoDepCondition =
    (a, b) ⇒ N_DepConditionUnion(Option(a), Option(b))

  def any: ProtoDepCondition =
    N_DepConditionAny()

  def leaf[By <: Product](lensName: NameMetaAttr, byInst: By): ProtoDepCondition = {
    val byClName = byInst.getClass.getName
    val adapter = qAdapterRegistry.byName(byClName)
    N_DepConditionLeaf(modelCl.getName, lensName.value, byClName, ToByteString(adapter.encode(byInst)))
  }

  def request: ProtoDepCondition => N_HashSearchDepRequest =
    cond ⇒ N_HashSearchDepRequest(modelCl.getName, Option(cond))

  def conditionToHashSearchRequest: Condition[Model] ⇒ N_HashSearchDepRequest = cond ⇒
    request(conditionToDepCond(cond))

  def conditionToDepCond: Condition[Model] ⇒ ProtoDepCondition = {
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
