package ee.cone.c4gate.deep_session

import ee.cone.c4actor._
import ee.cone.c4assemble.Assemble
import ee.cone.c4gate.SessionAttrAccessFactory
import ee.cone.c4proto.Protocol

trait DeepSessionAttrApp
  extends SessionDataProtocolApp
    with DeepSessionAttrFactoryImplApp
    with DeepSessionDataAssembleApp

trait SessionDataProtocolAppBase


trait DeepSessionDataAssembleApp extends AssemblesApp {
  def mortal: MortalFactory

  def userModel: Class[_ <: Product]

  def roleModel: Class[_ <: Product]

  override def assembles: List[Assemble] =
    DeepSessionDataAssembles(mortal, userModel, roleModel) ::: super.assembles
}

trait DeepSessionAttrFactoryImplApp {
  def qAdapterRegistry: QAdapterRegistry

  def modelFactory: ModelFactory

  def modelAccessFactory: ModelAccessFactory

  def idGenUtil: IdGenUtil

  def sessionAttrAccessFactory: SessionAttrAccessFactory

  lazy val deepSessionAttrAccessFactory: DeepSessionAttrAccessFactory =
    new DeepSessionAttrAccessFactoryImpl(qAdapterRegistry, modelFactory, modelAccessFactory, idGenUtil, sessionAttrAccessFactory)
}
