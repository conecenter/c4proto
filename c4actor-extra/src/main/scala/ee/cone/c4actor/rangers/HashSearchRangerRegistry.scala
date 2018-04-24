package ee.cone.c4actor.rangers

import ee.cone.c4actor.{Ranger, RangerWithCl}

trait HashSearchRangerRegistryApp {
  def hashSearchRangerRegistry:HashSearchRangerRegistryApi
}

trait HashSearchRangersApp{
  def hashSearchRangers: List[RangerWithCl[_ <: Product, _]] = Nil
}

trait HashSearchRangerRegistryMix extends HashSearchRangerRegistryApp with HashSearchRangersApp{
  def hashSearchRangerRegistry: HashSearchRangerRegistryApi = HashSearchRangerRegistryImpl(hashSearchRangers)
}

trait HashSearchRangerRegistryApi {
  def getByCl[By <: Product, Field](byCl: Class[By], fieldCl: Class[Field]): Option[RangerWithCl[By, Field]]
  def getByName[By <: Product, Field](rangerName: String): Option[Ranger[By, Field]]
}

case class HashSearchRangerRegistryImpl(rangers: List[RangerWithCl[_ <: Product, _]]) extends HashSearchRangerRegistryApi {
  lazy val rangerMap: Map[(String, String), Ranger[_ <: Product, _]] = rangers.map(ranger ⇒ (ranger.byCl.getName, ranger.fieldCl.getName) → ranger).toMap

  lazy val byNameMap: Map[String, Ranger[_ <: Product, _]] = rangers.map(ranger ⇒ ranger.getClass.getName → ranger).toMap

  def getByCl[By <: Product, Field](byCl: Class[By], fieldCl: Class[Field]): Option[Ranger[By, Field]] = rangerMap.get((byCl.getName, fieldCl.getName)).map(_.asInstanceOf[Ranger[By, Field]])

  def getByName[By <: Product, Field](rangerName: String): Option[Ranger[By, Field]] = byNameMap.get(rangerName).map(_.asInstanceOf[Ranger[By, Field]])
}
