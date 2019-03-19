package ee.cone.c4external

import ee.cone.c4actor._
import ee.cone.c4assemble.Assemble
import ee.cone.c4external.joiners.{ExternalJoinersMix, ExternalOrigJoiner}
import ee.cone.c4proto.Protocol

trait ExternalMix extends ProtocolsApp with ExtModelsApp with AssemblesApp with ExternalJoinersMix {
  def toUpdate: ToUpdate
  def qAdapterRegistry: QAdapterRegistry

  override def protocols: List[Protocol] = ExternalProtocol :: super.protocols
  def externalPreprocessor = new ExtUpdatesPreprocessorImpl(toUpdate, qAdapterRegistry, extModels)
  override def assembles: List[Assemble] = extModels.map(ext ⇒ {
    val extName = ext.clName
    val id = qAdapterRegistry.byName(extName).id
    new ExternalOrigJoiner(ext.cl, id, qAdapterRegistry)()
  }

  ) ::: super.assembles
}
