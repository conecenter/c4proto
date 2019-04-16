package ee.cone.c4external

import ee.cone.c4actor._
import ee.cone.c4assemble.{Assemble, OrigKeyFactory}
import ee.cone.c4external.joiners.{ExternalJoinersMix, ExternalOrigJoiner}
import ee.cone.c4proto.Protocol

trait ExternalMix
  extends ProtocolsApp
    with ExtModelsApp
    with AssemblesApp
    with ExternalJoinersMix
    with OrigKeyFactoryApp
    with UpdateProcessorApp {
  def toUpdate: ToUpdate
  def qAdapterRegistry: QAdapterRegistry

  override def protocols: List[Protocol] = ExternalProtocol :: super.protocols

  override def updateProcessor: UpdateProcessor = new ExtUpdatesPreprocessor(toUpdate, qAdapterRegistry, extModels)

  override def origKeyFactory: OrigKeyFactory = new ExtOrigKeyFactory(indexUtil, extModels)
  override def assembles: List[Assemble] = extModels.map(ext ⇒ {
    val extName = ext.clName
    val id = qAdapterRegistry.byName(extName).id
    new ExternalOrigJoiner(ext.cl, id, qAdapterRegistry)()
  }

  ) ::: super.assembles
}
