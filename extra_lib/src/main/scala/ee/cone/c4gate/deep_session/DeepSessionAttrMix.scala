package ee.cone.c4gate.deep_session

import ee.cone.c4actor._
import ee.cone.c4assemble.Assemble
import ee.cone.c4gate.SessionAttrAccessFactory

trait DeepSessionAttrApp
  extends SessionDataProtocolApp
    with DeepSessionAttrFactoryImplApp
    with DeepSessionDataAssembleApp

trait SessionDataProtocolAppBase
trait DeepSessionAttrFactoryImplAppBase extends ComponentProviderApp with TxDeepRawDataLensApp {
  lazy val deepSessionAttrAccessFactory: DeepSessionAttrAccessFactory =
    resolveSingle(classOf[DeepSessionAttrAccessFactory])
}
trait TxDeepRawDataLensAppBase

trait DeepSessionDataAssembleApp extends AssemblesApp {
  def mortal: MortalFactory

  def userModel: Class[_ <: Product]

  def roleModel: Class[_ <: Product]

  override def assembles: List[Assemble] =
    DeepSessionDataAssembles(mortal, userModel, roleModel) ::: super.assembles
}
