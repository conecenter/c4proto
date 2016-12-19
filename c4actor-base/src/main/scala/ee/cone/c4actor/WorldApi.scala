package ee.cone.c4actor

import ee.cone.c4actor.Types.{Index, SrcId, World}

import scala.collection.immutable.Map

abstract class WorldKey[Item](default: Item) {
  def of(world: World): Item = world.getOrElse(this, default).asInstanceOf[Item]
}

object Types {
  type Values[V] = List[V]
  type Index[K,V] = Map[K,Values[V]]
  type SrcId = String
  type MultiSet[T] = Map[T,Int]
  type World = Map[WorldKey[_],Object]
}

object By {
  case class It[K,V](typeChar: Char, className: String) extends WorldKey[Index[K,V]](Map.empty)
  def srcId[V](cl: Class[V]): WorldKey[Index[SrcId,V]] = It[SrcId,V]('S', cl.getName)
  def unit[V](cl: Class[V]): WorldKey[Index[Unit,V]] = It[Unit,V]('U', cl.getName)
}
