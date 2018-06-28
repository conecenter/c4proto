package ee.cone.c4gate.deep_session

import ee.cone.c4actor._
import ee.cone.c4assemble.Assemble
import ee.cone.c4gate.SessionAttrAccessFactory
import ee.cone.c4proto.Protocol

trait DeepSessionAttrApp
  extends SessionDataProtocolApp
    with DeepSessionAttrFactoryImplApp
    with DeepSessionDataAssembleApp

trait SessionDataProtocolApp extends ProtocolsApp {
  override def protocols: List[Protocol] = DeepSessionDataProtocol :: super.protocols
}


trait DeepSessionDataAssembleApp extends AssemblesApp {
  def mortal: MortalFactory

  def userModel: Class[_ <: Product]

  def roleModel: Class[_ <: Product]

  override def assembles: List[Assemble] =
    DeepSessionDataAssembles(mortal, userModel, roleModel) ::: super.assembles
}

trait DeepSessionAttrFactoryImplApp {
  def qAdapterRegistry: QAdapterRegistry

  def defaultModelRegistry: DefaultModelRegistry

  def modelAccessFactory: ModelAccessFactory

  def uuidUtil: UUIDUtil

  def sessionAttrAccessFactory: SessionAttrAccessFactory

  lazy val deepSessionAttrAccessFactory: DeepSessionAttrAccessFactory =
    new DeepSessionAttrAccessFactoryImpl(qAdapterRegistry, defaultModelRegistry, modelAccessFactory, uuidUtil, sessionAttrAccessFactory)
}
