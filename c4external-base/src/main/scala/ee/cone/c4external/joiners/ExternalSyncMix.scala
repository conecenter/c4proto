package ee.cone.c4external.joiners

import ee.cone.c4actor.{AssemblesApp, QAdapterRegistry, ToUpdate}
import ee.cone.c4assemble.Assemble
import ee.cone.c4external.ExtDBSync
import ee.cone.dbadapter.DBAdapter

trait ExternalSyncMix extends AssemblesApp {
  def dbAdapter: DBAdapter
  def qAdapterRegistry: QAdapterRegistry
  def extDBSync: ExtDBSync
  def toUpdate: ToUpdate

  override def assembles: List[Assemble] =
    new ProduceExternalTransforms(dbAdapter, extDBSync, toUpdate) ::
      super.assembles
}
