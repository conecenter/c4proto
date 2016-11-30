package ee.cone.c4proto

import Types._

object Single {
  def apply[C](l: List[C]): C =
    if(l.tail.nonEmpty) throw new Exception("single expected") else l.head
}

object By {
  case class It[K,V](typeChar: Char, className: String) extends WorldKey[Index[K,V]](Map.empty)
  def srcId[V](cl: Class[V]): WorldKey[Index[SrcId,V]] = It[SrcId,V]('S', cl.getName)
  def unit[V](cl: Class[V]): WorldKey[Index[Unit,V]] = It[Unit,V]('U', cl.getName)
}

trait IndexFactory {
  def createJoinMapIndex[R<:Object,TK,RK](join: Join[R,TK,RK]): WorldPartExpression
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
  type World = Map[WorldKey[_],Object]
}

trait DataDependencyFrom[From] {
  def inputWorldKeys: Seq[WorldKey[From]]
}

trait DataDependencyTo[To] {
  def outputWorldKey: WorldKey[To]
}

trait Join[Result,JoinKey,MapKey]
  extends DataDependencyFrom[Index[JoinKey,Object]]
  with DataDependencyTo[Index[MapKey,Result]]
{
  def joins(in: Seq[Values[Object]]): Iterable[(MapKey,Result)]
  def sort(values: Iterable[Result]): List[Result]
}

abstract class Join2[T1,T2,R,TK,RK](
  t1: WorldKey[Index[TK,T1]],
  t2: WorldKey[Index[TK,T2]],
  val outputWorldKey: WorldKey[Index[RK,R]]
) extends Join[R,TK,RK] {
  def join(a1: Values[T1], a2: Values[T2]): Values[(RK,R)]
  //
  def joins(in: Seq[Values[Object]]): Iterable[(RK,R)] = in match {
    case Seq(a1, a2) ⇒
      join(a1.asInstanceOf[Values[T1]], a2.asInstanceOf[Values[T2]])
  }
  def inputWorldKeys: Seq[WorldKey[Index[TK,Object]]] =
    Seq(t1, t2).asInstanceOf[Seq[WorldKey[Index[TK,Object]]]]
}

////
// moment -> mod/index -> key/srcId -> value -> count

trait WorldPartExpression extends DataDependencyFrom[_] with DataDependencyTo[_] {
  def transform(transition: WorldTransition): WorldTransition
}
case class WorldTransition(
  prev: World,
  diff: Map[WorldKey[_],Map[Object,Boolean]],
  current: World
)

trait Reducer {
  def reduce(prev: World, replaced: Map[WorldKey[_],Index[Object,Object]]): World
}