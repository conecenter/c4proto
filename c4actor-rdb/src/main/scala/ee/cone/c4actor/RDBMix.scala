
package ee.cone.c4actor

import ee.cone.c4actor.rdb_impl._
import ee.cone.c4assemble.Assemble

trait ExternalDBOptionsApp {
  def externalDBOptions: List[ExternalDBOption] = Nil
}

trait ToExternalDBSyncApp extends ToExternalDBSyncAutoApp with RDBSyncApp with AssemblesApp {
  def externalDBOptions: List[ExternalDBOption]

  override def assembles: List[Assemble] =
    ToExternalDBAssembles(externalDBOptions) ::: super.assembles
}

trait FromExternalDBSyncApp extends FromExternalDBSyncAutoApp with RDBSyncApp with ExternalDBOptionsApp with AssemblesApp {
  import rdbOptionFactory._
  override def externalDBOptions: List[ExternalDBOption] =
    dbProtocol(FromExternalDBProtocol) ::
      fromDB(classOf[FromExternalDBProtocol.B_DBOffset]) ::
      super.externalDBOptions
  override def assembles: List[Assemble] = new FromExternalDBSyncAssemble :: super.assembles
}

trait RDBSyncApp extends ToStartApp with ToInjectApp {
  def toUpdate: ToUpdate
  def externalDBFactory: ExternalDBFactory
  def externalDBOptions: List[ExternalDBOption]

  lazy val rdbOptionFactory = new RDBOptionFactoryImpl(toUpdate)

  lazy val externalDBSyncClient = new ExternalDBSyncClient(externalDBFactory)
  override def toInject: List[ToInject] = externalDBSyncClient :: super.toInject
  override def toStart: List[Executable] = externalDBSyncClient :: super.toStart
}
