
package ee.cone.c4assemble

import Types._

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.concurrent.{ExecutionContext, Future}

case class AssembleOptions(srcId: String, @deprecated isParallel: Boolean, threadCount: Long)

trait IndexUtil extends Product {
  def joinKey(was: Boolean, keyAlias: String, keyClassName: String, valueClassName: String): JoinKey
  def isEmpty(index: Index): Boolean
  def keySet(index: Index): Set[Any]
  def mergeIndex(l: DPIterable[Index]): Index
  def getValues(index: Index, key: Any, warning: String): Values[Product] //m
  def nonEmpty(index: Index, key: Any): Boolean //m
  def removingDiff(index: Index, key: Any): Index
  def result(key: Any, product: Product, count: Int): Index //m
  type Partitioning = Seq[(Boolean,()=>DPIterable[Product])]
  def partition(currentIndex: Index, diffIndex: Index, key: Any, warning: String): Partitioning  //m
  def nonEmptySeq: Seq[Unit] //m
  def mayBeParVector[V](iterable: Set[V], options: AssembleOptions): DPIterable[V]
  def mayBePar[V](iterable: Seq[V], options: AssembleOptions): Seq[V]
  def mayBePar[V](seq: Seq[V]): DPIterable[V]
  //
  def preIndex(seq: Seq[Index]): Index
  def buildIndex(joinRes: Index): Index
  def keyIteration(seq: Seq[Index], executionContext: OuterExecutionContext): (Any=>Seq[Index])=>Future[Index]
  //
  def getInstantly(future: Future[Index]): Index
}

trait OuterExecutionContext {
  def value: ExecutionContext
  def threadCount: Long
}

object Types {
  type Values[V] = Seq[V]
  type Each[V] = V
  type DMap[K,V] = Map[K,V] //ParMap[K,V]
  type DPIterable[V] = Iterable[V]
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
  def updated(worldKey: AssembledKey, value: Future[Index]): ReadModel=>ReadModel
  def isEmpty(implicit executionContext: ExecutionContext): ReadModel=>Future[Boolean]
  def op(op: (MMap,MMap)=>MMap): (ReadModel,ReadModel)=>ReadModel
  def ready(implicit executionContext: ExecutionContext): ReadModel=>Future[ReadModel]
  def toMap: ReadModel=>Map[AssembledKey,Index]
}

trait ReadModel {
  def getFuture(key: AssembledKey): Option[Future[Index]]
}

trait Getter[C,+I] {
  def of: C => I
}

object OrEmptyIndex {
  def apply(opt: Option[Future[Index]]): Future[Index] =
    opt.getOrElse(Future.successful(emptyIndex))
}
abstract class AssembledKey extends Product {
  def of(model: ReadModel): Future[Index] = OrEmptyIndex(model.getFuture(this))
}
trait WorldPartExpression extends WorldPartRule {
  def transform(transition: WorldTransition): WorldTransition
}
//object WorldTransition { type Diff = Map[AssembledKey[_],IndexDiff[Object,_]] } //Map[AssembledKey[_],Index[Object,_]] //Map[AssembledKey[_],Map[Object,Boolean]]
case class WorldTransition(
  prev: Option[WorldTransition],
  diff: ReadModel,
  result: ReadModel,
  profiling: JoiningProfiling,
  log: Future[ProfilingLog],
  executionContext: OuterExecutionContext,
  taskLog: List[AssembledKey]
)

trait JoiningProfiling extends Product {
  type Res = Long
  def time: Long
  def handle(join: Join, stage: Long, start: Long, joinRes: Res, wasLog: ProfilingLog): ProfilingLog
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

trait DataDependencyProvider {
  def getRules: List[WorldPartRule]
}

abstract class Join(
  val assembleName: String,
  val name: String,
  val inputWorldKeys: Seq[AssembledKey],
  val outputWorldKey: AssembledKey
) extends DataDependencyFrom[Index]
  with DataDependencyTo[Index]
{
  type DiffIndexRawSeq = Seq[Index]
  type Result = (Int,Seq[Index]) => Future[Index]
  def joins(diffIndexRawSeq: DiffIndexRawSeq, executionContext: OuterExecutionContext): Result
}

trait Assemble {
  def dataDependencies: IndexFactory => List[WorldPartRule with DataDependencyTo[_]]
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
class byEq[T](value: T) extends StaticAnnotation
class was extends StaticAnnotation
class distinct extends StaticAnnotation
class ns(key: String) extends StaticAnnotation

trait ExpressionsDumper[To] {
  def dump(expressions: List[DataDependencyTo[_] with DataDependencyFrom[_]]): To
}

sealed abstract class AbstractAll
case object All extends AbstractAll

/**
  * !!! bug
  * if there is *All joiner arguments
  *  and not all arguments are Values[]
  *  and all non-All arguments are Values[]
  *  and all non-All arguments are empty
  *  and joiner will return non-Nil
  *  then joiner will break the world
  * In other words:
  *  if there's Each[*All], then You either use also Each[non-All] or use if(isEmpty)-Nil-else explicitly
  * todo:
  *  make @by[T@all] or @by[All[T]] and check in generate the right condition
  *  instead of 'if(eachParams.nonEmpty)'
  * or make higher-order assembles and remove *All
  */

//class JoinRes(val byKey: Any, val productHashed: PreHashed[Product], val count: Int)
trait MergeableAssemble {
  def mergeKey: String
}
trait BasicMergeableAssemble extends MergeableAssemble {
  def mergeKeyAddClasses: List[Class[_]]
  def mergeKeyAddString: String
  def mergeKey: String = s"${(getClass::mergeKeyAddClasses).map(_.getName).mkString("-")}#$mergeKeyAddString"
}
trait CallerAssemble {
  def subAssembles: List[Assemble] = Nil
}
trait SubAssemble[R<:Product] {
  type Result = _=>Values[(_,R)]
  def result: Result
  def resultKey: IndexFactory=>JoinKey = throw new Exception("never here")
}

class CanCallToValues
class CanCallToEach
trait EachSubAssemble[R<:Product] extends SubAssemble[R] {
  def call(implicit can: CanCallToEach): Each[R] = throw new Exception("never here")
}
trait ValuesSubAssemble[R<:Product] extends SubAssemble[R] {
  def call(implicit can: CanCallToValues): Values[R] = throw new Exception("never here")
}
