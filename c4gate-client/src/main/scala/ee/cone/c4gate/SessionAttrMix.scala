package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4assemble.Assemble
import ee.cone.c4proto.Protocol

trait SessionAttrApp extends SessionDataProtocolApp
  with SessionDataAssembleApp
  with SessionAttrAccessFactoryImplApp

trait SessionDataProtocolApp extends ProtocolsApp {
  override def protocols: List[Protocol] =
    SessionDataProtocol :: super.protocols
}

trait SessionDataAssembleApp extends AssemblesApp {
  def mortal: MortalFactory

  override def assembles: List[Assemble] =
    SessionDataAssembles(mortal) ::: super.assembles
}

trait SessionAttrAccessFactoryImplApp {
  def qAdapterRegistry: QAdapterRegistry
  def defaultModelRegistry: DefaultModelRegistry
  def modelAccessFactory: ModelAccessFactory
  def idGenUtil: IdGenUtil

  lazy val sessionAttrAccessFactory: SessionAttrAccessFactory =
    new SessionAttrAccessFactoryImpl(qAdapterRegistry,defaultModelRegistry,modelAccessFactory,idGenUtil)
}