
package ee.cone.c4actor

import ee.cone.c4actor.rdb_impl._

trait ToExternalDBSyncApp extends RDBSyncApp with `The ToExternalDBTxAssemble`
  with `The ToExternalDBProtocol` with `The ToExternalDBAssembles`

trait FromExternalDBSyncApp extends RDBSyncApp with `The DefaultFromExternalDBOptionProvider`
  with `The FromExternalDBProtocol` with `The FromExternalDBSyncAssemble`

trait RDBSyncApp extends `The ExternalDBSyncClientInject`
  with `The ExternalDBSyncClientExecutable` with `The ExternalDBSyncClientImpl`
  with `The RDBOptionFactoryImpl`
