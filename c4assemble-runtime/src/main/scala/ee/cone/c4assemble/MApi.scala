
package ee.cone.c4assemble

import scala.collection.immutable.Map
import Types._

object Types {
  type Values[V] = List[V]
  type Index[K,V] = Map[K,Values[V]]
  type World = Map[WorldKey[_],Object]
}

trait Lens[C,I] {
  def of(container: C): I
  def modify(f: I⇒I)(container: C): C
}

abstract class WorldKey[Item](default: Item) extends Lens[World,Item] {
  def of(world: World): Item = world.getOrElse(this, default).asInstanceOf[Item]
  def modify(f: Item⇒Item)(world: World): World =
    world + (this → f(of(world)).asInstanceOf[Object])
}


trait WorldPartExpression /*[From,To] extends DataDependencyFrom[From] with DataDependencyTo[To]*/ {
  def transform(transition: WorldTransition): WorldTransition
}
case class WorldTransition(
  prev: World,
  diff: Map[WorldKey[_],Map[Object,Boolean]],
  current: World
)

trait IndexFactory {
  def createJoinMapIndex[R<:Object,TK,RK](join: Join[R,TK,RK], sort: Iterable[R]⇒Values[R]):
  WorldPartExpression
    with DataDependencyFrom[Index[TK, Object]]
    with DataDependencyTo[Index[RK, R]]
}

trait DataDependencyFrom[From] {
  def inputWorldKeys: Seq[WorldKey[From]]
}

trait DataDependencyTo[To] {
  def outputWorldKey: WorldKey[To]
}

class Join[Result,JoinKey,MapKey](
  val inputWorldKeys: Seq[WorldKey[Index[JoinKey, Object]]],
  val outputWorldKey: WorldKey[Index[MapKey, Result]],
  val joins: (JoinKey, Seq[Values[Object]]) ⇒ Iterable[(MapKey,Result)]
) extends DataDependencyFrom[Index[JoinKey,Object]]
  with DataDependencyTo[Index[MapKey,Result]]

trait Sorts {
  def get[R](cl: Class[R]): Iterable[R] ⇒ Values[R]
  def add[R](cl: Class[R], value: Iterable[R] ⇒ Values[R]): Sorts
}

trait Assemble {
  def sorts: Sorts⇒Sorts = ???
  def dataDependencies: (IndexFactory, Sorts) ⇒ List[DataDependencyTo[_]] = ???
}

case class MacroJoinKey[K,V](keyAlias: String, keyClassName: String, valueClassName: String)
  extends WorldKey[Index[K,V]](Map.empty)

class by[T]