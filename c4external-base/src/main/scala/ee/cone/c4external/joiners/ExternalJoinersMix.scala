package ee.cone.c4external.joiners

import ee.cone.c4actor.{AssemblesApp, QAdapterRegistry}
import ee.cone.c4assemble.Assemble
import ee.cone.c4external.{ExtDBSync, ExternalId}
import ee.cone.dbadapter.DBAdapter

trait ExternalJoinersMix extends AssemblesApp {
  def dbAdapter: DBAdapter
  def qAdapterRegistry: QAdapterRegistry
  def extDBSync: ExtDBSync
  def externalTickMillis: Long

  private lazy val externalId: ExternalId = dbAdapter.externalId

  override def assembles: List[Assemble] =
    new ExternalAllAssemble(externalId.uName, externalId) ::
      new ExtUpdatesToSyncAssemble(externalId.uName, externalId) ::
      new DeleteContainerCreation(externalId.uName, externalId, qAdapterRegistry)() ::
      new RequestResolving(externalId.uName, externalId) ::
      new ProduceExternalTransforms(dbAdapter, extDBSync, externalTickMillis) ::
      super.assembles
}
