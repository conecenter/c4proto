package ee.cone.c4ui.dep

import ee.cone.c4actor.dep.request.ContextIdRequestProtocol
import ee.cone.c4actor.dep._
import ee.cone.c4actor.dep_impl.AskByPKsApp
import ee.cone.c4actor._
import ee.cone.c4gate.SessionDataProtocol.U_RawSessionData
import ee.cone.c4gate.deep_session.DeepSessionDataProtocol.{U_RawRoleData, U_RawUserData}
import ee.cone.c4proto.Protocol

trait SessionAttrAskUtility {
  def sessionAttrAskFactory: SessionAttrAskFactoryApi
}

trait CurrentTimeAskUtility {
  def currentTimeAskFactory: CurrentTimeAskFactoryApi
}

trait SessionAttrAskMix extends SessionAttrAskUtility with CommonRequestUtilityApi with AskByPKsApp with AskByPKFactoryApp with ProtocolsApp with DepFactoryApp{


  override def protocols: List[Protocol] = ContextIdRequestProtocol :: super.protocols

  def qAdapterRegistry: QAdapterRegistry

  def modelFactory: ModelFactory

  def modelAccessFactory: ModelAccessFactory

  def idGenUtil: IdGenUtil

  private lazy val rawDataAsk: AskByPK[U_RawSessionData] = askByPKFactory.forClass(classOf[U_RawSessionData])
  private lazy val userDataAsk: AskByPK[U_RawUserData] = askByPKFactory.forClass(classOf[U_RawUserData])
  private lazy val roleDataAsk: AskByPK[U_RawRoleData] = askByPKFactory.forClass(classOf[U_RawRoleData])

  override def askByPKs: List[AbstractAskByPK] = rawDataAsk :: userDataAsk :: roleDataAsk :: super.askByPKs

  def sessionAttrAskFactory: SessionAttrAskFactoryApi = SessionAttrAskFactoryImpl(qAdapterRegistry, modelFactory, modelAccessFactory, commonRequestUtilityFactory, rawDataAsk, userDataAsk, roleDataAsk, idGenUtil, depFactory)
}

trait CurrentTimeAskMix extends CurrentTimeAskUtility {
  def currentTimeAskFactory: CurrentTimeAskFactoryApi = CurrentTimeAskFactoryImpl
}
