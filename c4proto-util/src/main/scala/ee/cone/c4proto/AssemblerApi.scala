package ee.cone.c4proto

import Types._

case class VoidBy[K]() extends WorldKey[Index[K,Unit]](Map.empty)
object BySrcId {
  case class It[V](className: String) extends WorldKey[Index[SrcId,V]](Map.empty)
  def apply[V](cl: Class[V]): WorldKey[Index[SrcId,V]] = It(cl.getName)
}

trait IndexFactory {
  def createJoinMapIndex[T1,T2,R<:Object,TK,RK](join: Join2[T1,T2,R,TK,RK]): BaseCoHandler
}

abstract class WorldKey[Item](default: Item) {
  def of(world: World): Item = world.getOrElse(this, default).asInstanceOf[Item]
}
/*
abstract class IndexWorldKey[K,V](empty: V) extends WorldKey[Map[K,Values[V]]] {
  def of(world: Map[WorldKey[_],Map[K,Values[V]]]): V = world.getOrElse(this,Map.empty)
}*/
object Types {
  type Values[V] = List[V]
  type Index[K,V] = Map[K,Values[V]]
  type SrcId = String
  type MultiSet[T] = Map[T,Int]
  type World = Map[WorldKey[_],Index[Object,Object]]
}

abstract class Join2[T1,T2,R,TK,RK](
  val t1: WorldKey[Index[TK,T1]],
  val t2: WorldKey[Index[TK,T2]],
  val r: WorldKey[Index[RK,R]]
) {
  def join(a1: Values[T1], a2: Values[T2]): Values[(RK,R)]
  def sort(values: Iterable[R]): List[R]
}

case object ProtocolKey extends EventKey[Protocol]

////
// moment -> mod/index -> key/srcId -> value -> count

case object WorldPartExpressionKey extends EventKey[WorldPartExpression]
trait WorldPartExpression {
  def inputWorldKeys: Seq[WorldKey[_]]
  def outputWorldKey: WorldKey[_]
  def transform(transition: WorldTransition): WorldTransition
}
case class WorldTransition(
  prev: World,
  diff: Map[WorldKey[_],Map[Object,Boolean]],
  current: World
)

