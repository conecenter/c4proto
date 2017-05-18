
package ee.cone.c4actor

import ee.cone.c4actor.rdb_impl._
import ee.cone.c4assemble.Assemble
import ee.cone.c4proto.Protocol

trait ExternalDBOptionsApp {
  def externalDBOptions: List[ExternalDBOption] = Nil
}

trait ToExternalDBSyncApp extends RDBSyncApp with AssemblesApp with ProtocolsApp {
  def externalDBOptions: List[ExternalDBOption]

  override def assembles: List[Assemble] =
    ToExternalDBAssembles(externalDBOptions) ::: super.assembles
  override def protocols: List[Protocol] = ToExternalDBProtocol :: super.protocols
}

trait FromExternalDBSyncApp extends RDBSyncApp with ExternalDBOptionsApp with ProtocolsApp with AssemblesApp {
  import rdbOptionFactory._
  override def externalDBOptions: List[ExternalDBOption] =
    dbProtocol(FromExternalDBProtocol) ::
      fromDB(classOf[FromExternalDBProtocol.DBOffset]) ::
      super.externalDBOptions
  override def assembles: List[Assemble] = new FromExternalDBSyncAssemble :: super.assembles
  override def protocols: List[Protocol] = FromExternalDBProtocol :: super.protocols
}

trait RDBSyncApp extends ToStartApp with InitLocalsApp {
  def qMessages: QMessages
  def externalDBFactory: ExternalDBFactory
  def externalDBOptions: List[ExternalDBOption]

  lazy val rdbOptionFactory = new RDBOptionFactoryImpl(qMessages)

  private lazy val externalDBSyncClient = new ExternalDBSyncClient(externalDBFactory)
  override def initLocals: List[InitLocal] = externalDBSyncClient :: super.initLocals
  override def toStart: List[Executable] = externalDBSyncClient :: super.toStart
}
