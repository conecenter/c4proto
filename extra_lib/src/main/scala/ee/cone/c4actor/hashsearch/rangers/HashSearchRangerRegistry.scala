package ee.cone.c4actor.hashsearch.rangers

import ee.cone.c4actor.{ComponentProviderApp, QAdapterRegistry, QAdapterRegistryApp}
import ee.cone.c4di.{Component, ComponentsApp, c4}
import ee.cone.c4actor.ComponentProvider.provide

trait HashSearchRangerRegistryApp {
  def hashSearchRangerRegistry: HashSearchRangerRegistry
}

trait HashSearchRangersApp extends ComponentsApp {
  private lazy val hashSearchRangersComponent =
    provide(classOf[RangerWithClProvider], ()=>Seq(RangerWithClProvider(hashSearchRangers)))
  override def components: List[Component] = hashSearchRangersComponent :: super.components
  def hashSearchRangers: List[RangerWithCl[_ <: Product, _]] = Nil
}

trait HashSearchRangerRegistryMixBase extends ComponentProviderApp with HashSearchRangerRegistryApp with HashSearchRangersApp {
  def hashSearchRangerRegistry: HashSearchRangerRegistry = resolveSingle(classOf[HashSearchRangerRegistry])
}

trait HashSearchRangerRegistry {
  def getByByIdUntyped(byId: Long): Option[RangerWithCl[_ <: Product, _]]

  def getAll: List[RangerWithCl[Product, Any]]
}

case class RangerWithClProvider(values: List[RangerWithCl[_ <: Product, _]])

@c4("HashSearchRangerRegistryMix") final case class HashSearchRangerRegistryImpl(
  providers: List[RangerWithClProvider],
  qAdapterRegistry: QAdapterRegistry
) extends HashSearchRangerRegistry {
  private def rangers = providers.flatMap(_.values).distinct
  lazy val byIdMap: Map[Long, RangerWithCl[_ <: Product, _]] =
    rangers.map(ranger => qAdapterRegistry.byName(ranger.byCl.getName).id -> ranger).toMap

  def getByByIdUntyped(byId: Long): Option[RangerWithCl[_ <: Product, _]] =
    byIdMap.get(byId)

  def getAll: List[RangerWithCl[Product, Any]] = rangers.map(_.asInstanceOf[RangerWithCl[Product, Any]])
}
