package ee.cone.c4gate.dep

import ee.cone.c4actor.dep.{AbstractAskByPK, AskByPK, AskByPKFactoryApp, CommonRequestUtilityApi}
import ee.cone.c4actor.dep_impl.AskByPKsApp
import ee.cone.c4actor.{DefaultModelRegistry, ModelAccessFactory, QAdapterRegistry}
import ee.cone.c4gate.SessionDataProtocol.RawSessionData

trait SessionAttrAskUtility {
  def sessionAttrAskFactory: SessionAttrAskFactoryApi
}

trait CurrentTimeAskUtility {
  def currentTimeAskFactory: CurrentTimeAskFactoryApi
}

trait SessionAttrAskMix extends SessionAttrAskUtility with CommonRequestUtilityApi with AskByPKsApp with AskByPKFactoryApp {
  def qAdapterRegistry: QAdapterRegistry

  def defaultModelRegistry: DefaultModelRegistry

  def modelAccessFactory: ModelAccessFactory

  private lazy val rawDataAsk: AskByPK[RawSessionData] = askByPKFactory.forClass(classOf[RawSessionData])

  override def askByPKs: List[AbstractAskByPK] = rawDataAsk :: super.askByPKs

  def sessionAttrAskFactory: SessionAttrAskFactoryApi = SessionAttrAskFactoryImpl(qAdapterRegistry, defaultModelRegistry, modelAccessFactory, commonRequestUtilityFactory, rawDataAsk)
}

trait CurrentTimeAskMix extends CurrentTimeAskUtility {
  def currentTimeAskFactory: CurrentTimeAskFactoryApi = CurrentTimeAskFactoryImpl
}
