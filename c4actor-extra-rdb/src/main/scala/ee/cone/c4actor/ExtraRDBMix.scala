
package ee.cone.c4actor

import ee.cone.c4proto.{Component, ComponentsApp}
import ee.cone.c4actor.ComponentProvider._

trait FromExternalDBSyncApp extends ee.cone.c4actor.rdb_impl.FromExternalDBSyncApp with RDBSyncApp
trait ToExternalDBSyncApp extends ee.cone.c4actor.rdb_impl.ToExternalDBSyncApp with RDBSyncApp
trait RDBSyncApp extends ComponentsApp with ExternalDBOptionsApp with ComponentProviderApp { // may be to other module
  lazy val rdbOptionFactory: RDBOptionFactory = resolveSingle(classOf[RDBOptionFactory])
  lazy val externalDBSyncClient: ExternalDBClient = resolveSingle(classOf[ExternalDBClient])

  def externalDBFactory: ExternalDBFactory
  private lazy val externalDBFactoryComponent = provide(classOf[ExternalDBFactory],()=>List(externalDBFactory))
  override def components: List[Component] = externalDBFactoryComponent :: super.components
}
trait ExternalDBOptionsApp extends ComponentsApp{
  def externalDBOptions: List[ExternalDBOption] = Nil
  private lazy val externalDBOptionsComponent = provide(classOf[ExternalDBOption],()=>externalDBOptions)
  override def components: List[Component] = externalDBOptionsComponent :: super.components
}



// def externalDBFactory: ExternalDBFactory //provide
// externalDBOptions: List[ExternalDBOption] //provide