package ee.cone.c4actor.dep

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.CtxType.ContextId
import ee.cone.c4actor.dep.request.ByClassNameRequestProtocol.ByClassNameRequest
import ee.cone.c4actor.dep.request.ByPKRequestProtocol.ByPKRequest
import ee.cone.c4actor.dep.request.ContextIdRequestProtocol.ContextIdRequest

trait CommonRequestUtilityFactory {
  def askByClassName[A](Class: Class[A], from: Int, to: Int): Dep[List[A]]

  def askByPK[A](Class: Class[A], srcId: SrcId): Dep[Option[A]]

  def askContextId: Dep[ContextId]
}

case object CommonRequestUtilityFactoryImpl extends CommonRequestUtilityFactory{
  def askByClassName[A](Class: Class[A], from: Int = -1, to: Int = -1): Dep[List[A]] = new RequestDep[List[A]](ByClassNameRequest(Class.getName, from, to))

  def askByPK[A](Class: Class[A], srcId: SrcId): Dep[Option[A]] = new RequestDep[Option[A]](ByPKRequest(Class.getName, srcId))

  def askContextId: Dep[ContextId] = new RequestDep[ContextId](ContextIdRequest())
}

trait CommonRequestUtility {
  def commonRequestUtilityFactory: CommonRequestUtilityFactory
}

trait CommonRequestUtilityMix {
  def commonRequestUtilityFactory: CommonRequestUtilityFactory = CommonRequestUtilityFactoryImpl
}