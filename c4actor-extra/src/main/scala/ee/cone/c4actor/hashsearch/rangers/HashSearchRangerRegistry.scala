package ee.cone.c4actor.hashsearch.rangers

import ee.cone.c4actor.{QAdapterRegistry, QAdapterRegistryApp}

trait HashSearchRangerRegistryApp {
  def hashSearchRangerRegistry: HashSearchRangerRegistryApi
}

trait HashSearchRangersApp {
  def hashSearchRangers: List[RangerWithCl[_ <: Product, _]] = Nil
}

trait HashSearchRangerRegistryMix extends HashSearchRangerRegistryApp with HashSearchRangersApp  with QAdapterRegistryApp{

  def hashSearchRangerRegistry: HashSearchRangerRegistryApi = HashSearchRangerRegistryImpl(hashSearchRangers.distinct, qAdapterRegistry)
}

trait HashSearchRangerRegistryApi {
  def getByCl[By <: Product, Field](byCl: Class[By], fieldCl: Class[Field]): Option[RangerWithCl[By, Field]]

  def getByName[By <: Product, Field](rangerName: String): Option[RangerWithCl[By, Field]]

  def getByById[By <: Product, Field](byId: Long): Option[RangerWithCl[By, Field]]
}

case class HashSearchRangerRegistryImpl(rangers: List[RangerWithCl[_ <: Product, _]], qAdapterRegistry: QAdapterRegistry) extends HashSearchRangerRegistryApi {
  lazy val rangerMap: Map[(String, String), RangerWithCl[_ <: Product, _]] =
    rangers.map(ranger ⇒ (ranger.byCl.getName, ranger.fieldCl.getName) → ranger).toMap

  lazy val byNameMap: Map[String, RangerWithCl[_ <: Product, _]] =
    rangers.map(ranger ⇒ ranger.getClass.getName → ranger).toMap

  lazy val byIdMap: Map[Long, RangerWithCl[_ <: Product, _]] =
    rangers.map(ranger ⇒ qAdapterRegistry.byName(ranger.byCl.getName).id → ranger).toMap

  def getByCl[By <: Product, Field](byCl: Class[By], fieldCl: Class[Field]): Option[RangerWithCl[By, Field]] =
    rangerMap.get((byCl.getName, fieldCl.getName)).map(_.asInstanceOf[RangerWithCl[By, Field]])

  def getByName[By <: Product, Field](rangerName: String): Option[RangerWithCl[By, Field]] =
    byNameMap.get(rangerName).map(_.asInstanceOf[RangerWithCl[By, Field]])

  def getByById[By <: Product, Field](byId: Long): Option[RangerWithCl[By, Field]] =
    byIdMap.get(byId).map(_.asInstanceOf[RangerWithCl[By, Field]])
}
