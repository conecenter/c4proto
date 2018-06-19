
package ee.cone.c4assemble

import collection.immutable.{Iterable, Map, Seq, TreeMap}
import Types.{DMultiSet, _}
import ee.cone.c4actor.PreHashed

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.collection.{GenIterable, GenSeq}
import scala.collection.parallel.immutable.{ParIterable, ParMap}

object Types {
  type Values[V] = Seq[V]
  type Each[V] = V
  type DMap[K,V] = Map[K,V] //ParMap[K,V]
  type DMultiSet = Map[PreHashed[Product],Int]
  type Index = DMap[Any,DMultiSet]
  type ReadModel = DMap[AssembledKey,Index]

  type Compose[V] = (V,V)⇒V
  def emptyDMap[K,V]: DMap[K,V] = Map.empty
  def emptyReadModel: ReadModel = emptyDMap
  def emptyIndex: Index = emptyDMap
  def emptyEachIndex: Index = emptyDMap
}

trait Getter[C,+I] {
  def of: C ⇒ I
}

abstract class AssembledKey extends Getter[ReadModel,Index] with Product {
  def of: ReadModel ⇒ Index = world ⇒ world.getOrElse(this, emptyIndex)
}

trait WorldPartExpression /*[From,To] extends DataDependencyFrom[From] with DataDependencyTo[To]*/ {
  def transform(transition: WorldTransition): WorldTransition
}
//object WorldTransition { type Diff = Map[AssembledKey[_],IndexDiff[Object,_]] } //Map[AssembledKey[_],Index[Object,_]] //Map[AssembledKey[_],Map[Object,Boolean]]
case class WorldTransition(prev: Option[WorldTransition], diff: ReadModel, result: ReadModel, isParallel: Boolean)

trait AssembleProfiler {
  def get(ruleName: String): String ⇒ Int ⇒ Unit
}

trait IndexFactory {
  def createJoinMapIndex(join: Join):
  WorldPartExpression
    with DataDependencyFrom[Index]
    with DataDependencyTo[Index]

  def partition(current: Option[DMultiSet], diff: Option[DMultiSet]): Iterable[(Boolean,GenIterable[PreHashed[Product]])]
  def wrapIndex: Object ⇒ Any ⇒ Option[DMultiSet]
  def wrapValues: Option[DMultiSet] ⇒ Values[Product]
  def nonEmptySeq: Seq[_]
  def keySet(indexSeq: Seq[Index]): Set[Any]
  def mergeIndex: Compose[Index]
  def diffFromJoinRes: ParIterable[JoinRes]⇒Option[Index]
  def result(key: Any, product: Product, count: Int): JoinRes
}

trait DataDependencyFrom[From] {
  def assembleName: String
  def name: String
  def inputWorldKeys: Seq[AssembledKey]
}

trait DataDependencyTo[To] {
  def outputWorldKey: AssembledKey
}

class Join(
  val assembleName: String,
  val name: String,
  val inputWorldKeys: Seq[AssembledKey],
  val outputWorldKey: AssembledKey,
  val joins: (ParIterable[(Int,Seq[Index])], Seq[Index]) ⇒ ParIterable[JoinRes]
) extends DataDependencyFrom[Index]
  with DataDependencyTo[Index]

trait Assemble {
  def dataDependencies: IndexFactory ⇒ List[DataDependencyTo[_]] = ???
}

case class JoinKey(was: Boolean, keyAlias: String, keyClassName: String, valueClassName: String)
  extends AssembledKey

//@compileTimeOnly("not expanded")
class by[T] extends StaticAnnotation
class was extends StaticAnnotation

trait ExpressionsDumper[To] {
  def dump(expressions: List[DataDependencyTo[_] with DataDependencyFrom[_]]): To
}

sealed abstract class All
case object All extends All

class JoinRes(val byKey: Any, val productHashed: PreHashed[Product], val count: Int)
