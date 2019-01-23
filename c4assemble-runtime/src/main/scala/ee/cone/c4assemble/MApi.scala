
package ee.cone.c4assemble

import collection.immutable.{Iterable, Map, Seq, TreeMap}
import Types._

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.collection.{GenIterable, GenMap, GenSeq}
import scala.concurrent.Future

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
  private object EmptyReadModel extends ReadModelImpl(emptyDMap)
  //
  def emptyDMap[K,V]: DMap[K,V] = Map.empty
  def emptyReadModel: ReadModel = EmptyReadModel
  def emptyIndex: Index = EmptyIndex//emptyDMap
  //
  type ProfilingLog = List[Product]
  //
  implicit val canCallToValues: CanCallToValues = new CanCallToValues
  implicit val canCallToEach: CanCallToEach = new CanCallToEach
}

trait ReadModelUtil {
  type MMap = DMap[AssembledKey, Future[Index]]
  def create(inner: MMap): ReadModel
  def updated(worldKey: AssembledKey, value: Future[Index]): ReadModel⇒ReadModel
  def isEmpty: ReadModel⇒Future[Boolean]
  def op(op: (MMap,MMap)⇒MMap): (ReadModel,ReadModel)⇒ReadModel
  def ready: ReadModel⇒Future[ReadModel]
  def toMap: ReadModel⇒Map[AssembledKey,Index]
}

trait ReadModel {
  def getFuture(key: AssembledKey): Future[Index]
}

trait Getter[C,+I] {
  def of: C ⇒ I
}

abstract class AssembledKey extends Getter[ReadModel,Future[Index]] with Product {
  def of: ReadModel ⇒ Future[Index] = world ⇒ world.getFuture(this)
}

trait WorldPartExpression /*[From,To] extends DataDependencyFrom[From] with DataDependencyTo[To]*/ {
  def transform(transition: WorldTransition): WorldTransition
}
//object WorldTransition { type Diff = Map[AssembledKey[_],IndexDiff[Object,_]] } //Map[AssembledKey[_],Index[Object,_]] //Map[AssembledKey[_],Map[Object,Boolean]]
case class WorldTransition(prev: Option[WorldTransition], diff: ReadModel, result: ReadModel, isParallel: Boolean, profiling: JoiningProfiling, log: Future[ProfilingLog])

trait JoiningProfiling extends Product {
  def time: Long
  def handle(join: Join, stage: Long, start: Long, joinRes: DPIterable[Index], wasLog: ProfilingLog): ProfilingLog
}

trait IndexFactory {
  def createJoinMapIndex(join: Join):
  WorldPartExpression
    with DataDependencyFrom[Index]
    with DataDependencyTo[Index]

  def util: IndexUtil
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
  type MakeJoinKey = IndexFactory⇒JoinKey
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
class ns(key: String) extends StaticAnnotation

trait ExpressionsDumper[To] {
  def dump(expressions: List[DataDependencyTo[_] with DataDependencyFrom[_]]): To
}

sealed abstract class All
case object All extends All

//class JoinRes(val byKey: Any, val productHashed: PreHashed[Product], val count: Int)
trait CheckedAssemble {
  def dataDependencies: IndexFactory ⇒ List[DataDependencyTo[_]]
}
trait MergeableAssemble {
  def mergeKey: String
}
trait BasicMergeableAssemble extends MergeableAssemble {
  def mergeKeyAddClasses: List[Class[_]]
  def mergeKeyAddString: String
  def mergeKey: String = s"${(getClass::mergeKeyAddClasses).map(_.getName).mkString("-")}#$mergeKeyAddString"
}
trait CallerAssemble extends Assemble {
  def subAssembles: List[Assemble]
}
trait SubAssemble[R<:Product] extends Assemble {
  type Result = _⇒Values[(_,R)]
  def result: Result
  def resultKey: IndexFactory⇒JoinKey = throw new Exception("never here")
}
object Tupler

class CanCallToValues
class CanCallToEach
trait EachSubAssemble[R<:Product] extends SubAssemble[R] {
  def call(implicit can: CanCallToEach): Each[R] = throw new Exception("never here")
}
trait ValuesSubAssemble[R<:Product] extends SubAssemble[R] {
  def call(implicit can: CanCallToValues): Values[R] = throw new Exception("never here")
}
