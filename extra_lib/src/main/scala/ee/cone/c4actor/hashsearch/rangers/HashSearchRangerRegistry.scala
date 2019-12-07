package ee.cone.c4actor.hashsearch.rangers

import ee.cone.c4actor.{QAdapterRegistry, QAdapterRegistryApp}

trait HashSearchRangerRegistryApp {
  def hashSearchRangerRegistry: HashSearchRangerRegistry
}

trait HashSearchRangersApp {
  def hashSearchRangers: List[RangerWithCl[_ <: Product, _]] = Nil
}

trait HashSearchRangerRegistryMix extends HashSearchRangerRegistryApp with HashSearchRangersApp with QAdapterRegistryApp {

  def hashSearchRangerRegistry: HashSearchRangerRegistry = HashSearchRangerRegistryImpl(hashSearchRangers.distinct, qAdapterRegistry)
}

trait HashSearchRangerRegistry {
  def getByByIdUntyped(byId: Long): Option[RangerWithCl[_ <: Product, _]]

  def getAll: List[RangerWithCl[Product, Any]]
}

case class HashSearchRangerRegistryImpl(rangers: List[RangerWithCl[_ <: Product, _]], qAdapterRegistry: QAdapterRegistry) extends HashSearchRangerRegistry {
  lazy val byIdMap: Map[Long, RangerWithCl[_ <: Product, _]] =
    rangers.map(ranger => qAdapterRegistry.byName(ranger.byCl.getName).id -> ranger).toMap

  def getByByIdUntyped(byId: Long): Option[RangerWithCl[_ <: Product, _]] =
    byIdMap.get(byId)

  def getAll: List[RangerWithCl[Product, Any]] = rangers.map(_.asInstanceOf[RangerWithCl[Product, Any]])
}
