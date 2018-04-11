package ee.cone.c4gate.dep

import ee.cone.c4actor.{DefaultModelRegistry, ModelAccessFactory, QAdapterRegistry}
import ee.cone.c4actor.dep.CommonRequestUtility
import ee.cone.c4actor.dep.request.ByPKRequestHandlerApp
import ee.cone.c4gate.SessionDataProtocol.RawSessionData

trait SessionAttrAskUtility {
  def sessionAttrAskFactory: SessionAttrAskFactoryApi
}

trait CurrentTimeAskUtility {
  def currentTimeAskFactory: CurrentTimeAskFactoryApi
}

trait SessionAttrAskMix extends SessionAttrAskUtility with CommonRequestUtility with ByPKRequestHandlerApp {
  def qAdapterRegistry: QAdapterRegistry

  def defaultModelRegistry: DefaultModelRegistry

  def modelAccessFactory: ModelAccessFactory

  override def byPKClasses: List[Class[_ <: Product]] = classOf[RawSessionData] :: super.byPKClasses

  def sessionAttrAskFactory: SessionAttrAskFactoryApi = SessionAttrAskFactoryImpl(qAdapterRegistry, defaultModelRegistry, modelAccessFactory, commonRequestUtilityFactory)
}

trait CurrentTimeAskMix extends CurrentTimeAskUtility with CommonRequestUtility {
  def currentTimeAskFactory: CurrentTimeAskFactoryApi = CurrentTimeAskFactoryImpl(commonRequestUtilityFactory)
}
