package ee.cone.c4external

import ee.cone.c4actor._
import ee.cone.c4assemble.Assemble
import ee.cone.c4proto.Protocol

trait ExternalMix extends ProtocolsApp with UpdatesProcessorsApp with ExtModelsApp with AssemblesApp {
  def toUpdate: ToUpdate
  def qAdapterRegistry: QAdapterRegistry

  override def protocols: List[Protocol] = ExternalProtocol :: super.protocols
  def externalPreprocessor = new ExtUpdatesPreprocessorImpl(toUpdate, qAdapterRegistry, external)
  override def assembles: List[Assemble] = external.map(ext ⇒ {
    val extName = ext.getName
    val id = qAdapterRegistry.byName(extName).id
    new ExternalOrigJoiner(ext, id, qAdapterRegistry)()
  }

  ) ::: super.assembles
}
