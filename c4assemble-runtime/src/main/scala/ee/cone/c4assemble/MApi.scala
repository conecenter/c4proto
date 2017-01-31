
package ee.cone.c4assemble

import scala.collection.immutable.Map
import Types._

object Types {
  type Values[V] = List[V]
  type Index[K,V] = Map[K,Values[V]]
  type World = Map[WorldKey[_],Object]
}

trait Lens[C,I] {
  def of: C ⇒ I
  def modify: (I⇒I) ⇒ C⇒C
  def set: I ⇒ C⇒C
}

abstract class WorldKey[Item](default: Item) extends Lens[World,Item] {
  def of: World ⇒ Item = world ⇒ world.getOrElse(this, default).asInstanceOf[Item]
  def modify: (Item⇒Item) ⇒ World ⇒ World = f ⇒ world ⇒ set(f(of(world)))(world)
  def set: Item ⇒ World ⇒ World = value ⇒ _ + (this → value.asInstanceOf[Object])
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
  def createJoinMapIndex[T,R<:Product,TK,RK](join: Join[T,R,TK,RK]):
  WorldPartExpression
    with DataDependencyFrom[Index[TK, T]]
    with DataDependencyTo[Index[RK, R]]
}

trait DataDependencyFrom[From] {
  def inputWorldKeys: Seq[WorldKey[From]]
}

trait DataDependencyTo[To] {
  def outputWorldKey: WorldKey[To]
}

class Join[T,R,TK,RK](
  val inputWorldKeys: Seq[WorldKey[Index[TK, T]]],
  val outputWorldKey: WorldKey[Index[RK, R]],
  val joins: (TK, Seq[Values[T]]) ⇒ Iterable[(RK,R)]
) extends DataDependencyFrom[Index[TK,T]]
  with DataDependencyTo[Index[RK,R]]

trait Assemble {
  def dataDependencies: IndexFactory ⇒ List[DataDependencyTo[_]] = ???
}

case class JoinKey[K,V<:Product](keyAlias: String, keyClassName: String, valueClassName: String)
  extends WorldKey[Index[K,V]](Map.empty)

class by[T]