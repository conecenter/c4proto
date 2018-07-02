package ee.cone.c4gate.dep

import ee.cone.c4actor.dep.request.ContextIdRequestProtocol
import ee.cone.c4actor.dep.{AbstractAskByPK, AskByPK, AskByPKFactoryApp, CommonRequestUtilityApi}
import ee.cone.c4actor.dep_impl.AskByPKsApp
import ee.cone.c4actor.{DefaultModelRegistry, ModelAccessFactory, ProtocolsApp, QAdapterRegistry, UUIDUtil}
import ee.cone.c4gate.SessionDataProtocol.RawSessionData
import ee.cone.c4gate.deep_session.DeepSessionDataProtocol.{RawRoleData, RawUserData}
import ee.cone.c4proto.Protocol

trait SessionAttrAskUtility {
  def sessionAttrAskFactory: SessionAttrAskFactoryApi
}

trait CurrentTimeAskUtility {
  def currentTimeAskFactory: CurrentTimeAskFactoryApi
}

trait SessionAttrAskMix extends SessionAttrAskUtility with CommonRequestUtilityApi with AskByPKsApp with AskByPKFactoryApp with ProtocolsApp {


  override def protocols: List[Protocol] = ContextIdRequestProtocol :: super.protocols

  def qAdapterRegistry: QAdapterRegistry

  def defaultModelRegistry: DefaultModelRegistry

  def modelAccessFactory: ModelAccessFactory

  def uuidUtil: UUIDUtil

  private lazy val rawDataAsk: AskByPK[RawSessionData] = askByPKFactory.forClass(classOf[RawSessionData])
  private lazy val userDataAsk: AskByPK[RawUserData] = askByPKFactory.forClass(classOf[RawUserData])
  private lazy val roleDataAsk: AskByPK[RawRoleData] = askByPKFactory.forClass(classOf[RawRoleData])

  override def askByPKs: List[AbstractAskByPK] = rawDataAsk :: userDataAsk :: roleDataAsk :: super.askByPKs

  def sessionAttrAskFactory: SessionAttrAskFactoryApi = SessionAttrAskFactoryImpl(qAdapterRegistry, defaultModelRegistry, modelAccessFactory, commonRequestUtilityFactory, rawDataAsk, userDataAsk, roleDataAsk, uuidUtil)
}

trait CurrentTimeAskMix extends CurrentTimeAskUtility {
  def currentTimeAskFactory: CurrentTimeAskFactoryApi = CurrentTimeAskFactoryImpl
}
