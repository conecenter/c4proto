package ee.cone.c4external

import ee.cone.c4actor._
import ee.cone.c4assemble.Assemble
import ee.cone.c4external.joiners.{ExternalOrigJoiner, ExternalSyncMix, WriteToKafkaImpl}
import ee.cone.c4proto.Protocol

trait ExternalMix
  extends ProtocolsApp
    with ExtModelsApp
    with AssemblesApp
    with ExternalSyncMix
    with DefaultKeyFactoryApp
    with DefaultUpdateProcessorApp
    with ExtDBSyncApp {
  def toUpdate: ToUpdate
  def qAdapterRegistry: QAdapterRegistry
  def hashGen: HashGen

  override def protocols: List[Protocol] = ExternalProtocol :: super.protocols

  override def updateProcessor: UpdateProcessor = new ExtUpdatesPreprocessor(toUpdate, qAdapterRegistry, extModels)()

  override def origKeyFactory: KeyFactory = new ExtKeyFactory(indexUtil, extModels)
  override def assembles: List[Assemble] = extModels.map(ext ⇒ {
    val extName = ext.clName
    val id = qAdapterRegistry.byName(extName).id
    new ExternalOrigJoiner(ext.cl, id, qAdapterRegistry, new WriteToKafkaImpl(hashGen, id))()
  }

  ) ::: super.assembles
}
