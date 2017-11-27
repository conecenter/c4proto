
package ee.cone.c4assemble

import scala.collection.immutable.Map
import Types._
import ee.cone.c4assemble.TreeAssemblerTypes.MultiSet

import collection.immutable.{Iterable, Seq}
import scala.annotation.{StaticAnnotation, compileTimeOnly}

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

trait AssembleProfiler {
  def get(ruleName: String): String ⇒ Int ⇒ Unit
}

trait IndexFactory {
  def createJoinMapIndex[T,R<:Product,TK,RK](join: Join[T,R,TK,RK]):
  WorldPartExpression
    with DataDependencyFrom[Index[TK, T]]
    with DataDependencyTo[Index[RK, R]]
}

trait IndexValueMergerFactory {
  def create[R <: Product]: (Values[R],MultiSet[R]) ⇒ Values[R]
}

abstract class IndexValueMergerConfig(val from: Int)

trait DataDependencyFrom[From] {
  def assembleName: String
  def name: String
  def inputWorldKeys: Seq[AssembledKey[From]]
}

object HiddenC4Annotations {
  class c4component
  class listed
}
import HiddenC4Annotations._

trait DataDependencyTo[To] {
  def outputWorldKey: AssembledKey[To]
}

class Join[T,R,TK,RK](
  val assembleName: String,
  val name: String,
  val inputWorldKeys: Seq[AssembledKey[Index[TK, T]]],
  val outputWorldKey: AssembledKey[Index[RK, R]],
  val joins: (TK, Seq[Values[T]]) ⇒ Iterable[(RK,R)]
) extends DataDependencyTo[Index[RK,R]]
  with DataDependencyFrom[Index[TK,T]]

@c4component @listed abstract class Assemble {
  def dataDependencies: IndexFactory ⇒ List[DataDependencyTo[_]] = ???
}

case class JoinKey[K,V<:Product](keyAlias: String, keyClassName: String, valueClassName: String)
  extends AssembledKey[Index[K,V]](Map.empty)

//@compileTimeOnly("not expanded")
class by[T] extends StaticAnnotation
class assemble extends StaticAnnotation

@c4component @listed abstract class UnitExpressionsDumper extends ExpressionsDumper[Unit]
abstract class UMLExpressionsDumper extends ExpressionsDumper[String]

trait ExpressionsDumper[To] {
  def dump(expressions: List[DataDependencyTo[_] with DataDependencyFrom[_]]): To
}