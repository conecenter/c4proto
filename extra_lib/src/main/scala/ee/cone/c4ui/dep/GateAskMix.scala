package ee.cone.c4ui.dep

import ee.cone.c4actor._
import ee.cone.c4actor.dep._
import ee.cone.c4actor.dep.request.ContextIdRequestProtocolApp
import ee.cone.c4actor.dep_impl.AskByPKsApp
import ee.cone.c4gate.SessionDataProtocol.U_RawSessionData
import ee.cone.c4gate.deep_session.DeepSessionDataProtocol.{U_RawRoleData, U_RawUserData}

trait SessionAttrAskUtility {
  def sessionAttrAskFactory: SessionAttrAskFactory
}

trait CurrentTimeAskUtility {
  def currentTimeAskFactory: CurrentTimeAskFactory
}

trait SessionAttrAskCompAppBase

trait SessionAttrAskMix
  extends SessionAttrAskCompApp
    with ContextIdRequestProtocolApp
    with ComponentProviderApp {
  lazy val sessionAttrAskFactory: SessionAttrAskFactory = resolveSingle(classOf[SessionAttrAskFactory])
}

trait CurrentTimeAskCompAppBase

trait CurrentTimeAskMix extends CurrentTimeAskUtility with CurrentTimeAskCompApp with ComponentProviderApp {
  lazy val currentTimeAskFactory: CurrentTimeAskFactory = resolveSingle(classOf[CurrentTimeAskFactory])
}
