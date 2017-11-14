
package ee.cone.c4actor

import ee.cone.c4actor.rdb_impl._
import ee.cone.c4assemble.{Assemble,`The Assemble`}
import ee.cone.c4proto.Protocol

trait ExternalDBOptionsApp {
  def externalDBOptions: List[ExternalDBOption] = Nil
}

trait ToExternalDBSyncApp extends RDBSyncApp with `The Assemble` with ProtocolsApp {
  def externalDBOptions: List[ExternalDBOption]

  override def `the List of Assemble`: List[Assemble] =
    ToExternalDBAssembles(externalDBOptions) ::: super.`the List of Assemble`
  override def protocols: List[Protocol] = ToExternalDBProtocol :: super.protocols
}

trait FromExternalDBSyncApp extends RDBSyncApp with ExternalDBOptionsApp with ProtocolsApp with `The Assemble` {
  import `the RDBOptionFactory`._
  override def externalDBOptions: List[ExternalDBOption] =
    dbProtocol(FromExternalDBProtocol) ::
      fromDB(classOf[FromExternalDBProtocol.DBOffset]) ::
      super.externalDBOptions
  override def `the List of Assemble`: List[Assemble] = new FromExternalDBSyncAssemble :: super.`the List of Assemble`
  override def protocols: List[Protocol] = FromExternalDBProtocol :: super.protocols
}

trait RDBSyncApp extends `The ExternalDBSyncClientInject`
  with `The ExternalDBSyncClientExecutable` with `The ExternalDBSyncClientImpl`
  with `The RDBOptionFactoryImpl`
