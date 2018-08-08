
package ee.cone.c4assemble

import collection.immutable.{Iterable, Map, Seq, TreeMap}
import Types._

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.collection.{GenIterable, GenMap, GenSeq}

trait IndexUtil extends Product {
  def joinKey(was: Boolean, keyAlias: String, keyClassName: String, valueClassName: String): JoinKey
  def isEmpty(index: Index): Boolean
  def keySet(index: Index): Set[Any]
  def mergeIndex(l: DPIterable[Index]): Index
  def getValues(index: Index, key: Any, warning: String): Values[Product] //m
  def nonEmpty(index: Index, key: Any): Boolean //m
  def removingDiff(index: Index, key: Any): Index
  def result(key: Any, product: Product, count: Int): Index //m
  type Partitioning = Iterable[(Boolean,()⇒DPIterable[Product])]
  def partition(currentIndex: Index, diffIndex: Index, key: Any, warning: String): Partitioning  //m
  def nonEmptySeq: Seq[Unit] //m
  def invalidateKeySet(diffIndexSeq: Seq[Index]): Seq[Index] ⇒ DPIterable[Any] //m
}

object Types {
  type Values[V] = Seq[V]
  type Each[V] = V
  type DMap[K,V] = Map[K,V] //ParMap[K,V]
  type DPIterable[V] = GenIterable[V]
  trait Index //DMap[Any,DMultiSet]
  private object EmptyIndex extends Index
  type ReadModel = DMap[AssembledKey,Index]
  def emptyDMap[K,V]: DMap[K,V] = Map.empty
  def emptyReadModel: ReadModel = emptyDMap
  def emptyIndex: Index = EmptyIndex//emptyDMap
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
  def getOpt(ruleName: String, in: Seq[AssembledKey], out: AssembledKey): Option[String ⇒ Int ⇒ Unit] = None
}

trait IndexFactory {
  def createJoinMapIndex(join: Join):
  WorldPartExpression
    with DataDependencyFrom[Index]
    with DataDependencyTo[Index]

  def util: IndexUtil
/*
  def partition(current: Option[DMultiSet], diff: Option[DMultiSet]): Iterable[(Boolean,GenIterable[PreHashed[Product]])]
  def wrapIndex: Object ⇒ Any ⇒ Option[DMultiSet]
  def wrapValues: Option[DMultiSet] ⇒ Values[Product]
  def nonEmptySeq: Seq[_]
  def keySet(indexSeq: Seq[Index]): Set[Any]*/
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
  val joins: (DPIterable[(Int,Seq[Index])], Seq[Index]/*, IndexOpt*/) ⇒ DPIterable[Index]
) extends DataDependencyFrom[Index]
  with DataDependencyTo[Index]

trait Assemble {
  def dataDependencies: IndexFactory ⇒ List[DataDependencyTo[_]] = ???
}

trait JoinKey extends AssembledKey {
  def was: Boolean
  def keyAlias: String
  def keyClassName: String
  def valueClassName: String
  def withWas(was: Boolean): JoinKey
}

//@compileTimeOnly("not expanded")
class by[T] extends StaticAnnotation
class was extends StaticAnnotation
class distinct extends StaticAnnotation

trait ExpressionsDumper[To] {
  def dump(expressions: List[DataDependencyTo[_] with DataDependencyFrom[_]]): To
}

sealed abstract class All
case object All extends All

//class JoinRes(val byKey: Any, val productHashed: PreHashed[Product], val count: Int)
