
package ee.cone.c4actor

import ee.cone.c4actor.rdb_impl._
import ee.cone.c4assemble.Assemble


trait ExternalDBOptionsApp {
  def externalDBOptions: List[ExternalDBOption] = Nil
}

trait ToExternalDBSyncApp extends RDBSyncApp with `The ToExternalDBTxAssemble` with `The ToExternalDBProtocol` {
  def externalDBOptions: List[ExternalDBOption]

  override def `the List of Assemble`: List[Assemble] =
    ToExternalDBAssembles(externalDBOptions) ::: super.`the List of Assemble`
}

trait FromExternalDBSyncApp extends RDBSyncApp with ExternalDBOptionsApp with `The FromExternalDBProtocol` with `The FromExternalDBSyncAssemble` {
  import `the RDBOptionFactory`._
  override def externalDBOptions: List[ExternalDBOption] =
    dbProtocol(FromExternalDBProtocol) ::
      fromDB(classOf[FromExternalDBProtocol.DBOffset]) ::
      super.externalDBOptions
}

trait RDBSyncApp extends `The ExternalDBSyncClientInject`
  with `The ExternalDBSyncClientExecutable` with `The ExternalDBSyncClientImpl`
  with `The RDBOptionFactoryImpl`
