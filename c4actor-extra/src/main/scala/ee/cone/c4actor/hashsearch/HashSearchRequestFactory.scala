package ee.cone.c4actor.hashsearch

import ee.cone.c4actor.{NameMetaAttr, QAdapterRegistry}
import ee.cone.c4actor.dep.request.HashSearchDepRequestProtocol.{By, DepCondition, HashSearchDepRequest}
import ee.cone.c4proto.ToByteString

trait HashSearchDepRequestFactory[Model] {
  def intersect: (DepCondition, DepCondition) ⇒ DepCondition

  def union: (DepCondition, DepCondition) ⇒ DepCondition

  def any: DepCondition

  def leaf[By <: Product](lensName: NameMetaAttr, by: By): DepCondition

  def request: DepCondition ⇒ HashSearchDepRequest
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
}
