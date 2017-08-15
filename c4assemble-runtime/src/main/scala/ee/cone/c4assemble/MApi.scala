
package ee.cone.c4assemble

import scala.collection.immutable.Map
import Types._
import ee.cone.c4assemble.TreeAssemblerTypes.MultiSet

import collection.immutable.{Iterable,Seq}

object Types {
  type Values[V] = Seq[V]
  type Index[K,V] = Map[K,Values[V]]
  type ReadModel = Map[AssembledKey[_],Object]
}

trait Getter[C,+I] {
  def of: C ⇒ I
}

abstract class AssembledKey[+Item](default: Item) extends Getter[ReadModel,Item] {
  def of: ReadModel ⇒ Item = world ⇒ world.getOrElse(this, default).asInstanceOf[Item]
}

trait WorldPartExpression /*[From,To] extends DataDependencyFrom[From] with DataDependencyTo[To]*/ {
  def transform(transition: WorldTransition): WorldTransition
}
case class WorldTransition(
  prev: ReadModel,
  diff: Map[AssembledKey[_],Map[Object,Boolean]],
  current: ReadModel
)

trait IndexFactory {
  def createJoinMapIndex[T,R<:Product,TK,RK](join: Join[T,R,TK,RK]):
  WorldPartExpression
    with DataDependencyFrom[Index[TK, T]]
    with DataDependencyTo[Index[RK, R]]
}

trait IndexValueMergerFactory {
  def create[R <: Product]: (Values[R],MultiSet[R]) ⇒ Values[R]
}

trait DataDependencyFrom[From] {
  def inputWorldKeys: Seq[AssembledKey[From]]
}

trait DataDependencyTo[To] {
  def outputWorldKey: AssembledKey[To]
}

class Join[T,R,TK,RK](
  val inputWorldKeys: Seq[AssembledKey[Index[TK, T]]],
  val outputWorldKey: AssembledKey[Index[RK, R]],
  val joins: (TK, Seq[Values[T]]) ⇒ Iterable[(RK,R)]
) extends DataDependencyFrom[Index[TK,T]]
  with DataDependencyTo[Index[RK,R]]

trait Assemble {
  def dataDependencies: IndexFactory ⇒ List[DataDependencyTo[_]] = ???
}

case class JoinKey[K,V<:Product](keyAlias: String, keyClassName: String, valueClassName: String)
  extends AssembledKey[Index[K,V]](Map.empty)

class by[T]