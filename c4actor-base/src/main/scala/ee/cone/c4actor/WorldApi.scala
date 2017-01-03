package ee.cone.c4actor

import ee.cone.c4actor.Types.{Index, SrcId, World}

import scala.collection.immutable.Map

trait Lens[C,I] {
  def of(container: C): I
  def modify(f: I⇒I)(container: C): C
}

abstract class WorldKey[Item](default: Item) extends Lens[World,Item] {
  def of(world: World): Item = world.getOrElse(this, default).asInstanceOf[Item]
  def modify(f: Item⇒Item)(world: World): World =
    world + (this → f(of(world)).asInstanceOf[Object])
}
/*
class Transform(val get: World) {
  def apply[Item<:Object](key: WorldKey[Item])(f: Item⇒Item): Transform =
    new Transform(get + (key → f(key.of(get))))
}*/

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
}