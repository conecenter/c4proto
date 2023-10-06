
package ee.cone.c4assemble

import Types._

import scala.annotation.StaticAnnotation
import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}

case class AssembleOptions(srcId: String, @deprecated isParallel: Boolean, threadCount: Long)

trait IndexUtil {
  def joinKey(was: Boolean, keyAlias: String, keyClassName: String, valueClassName: String): JoinKey
  def isEmpty(index: Index): Boolean
  def valueCount(index: Index): Int
  def keyCount(index: Index): Int
  def keyIterator(index: Index): Iterator[Any]
  def mergeIndex(l: DPIterable[Index]): Index
  def zipMergeIndex(aDiffs: Seq[Index])(bDiffs: Seq[Index]): Seq[Index]
  def zipMergeIndexA(aDiffs: Seq[Index], bDiffs: Seq[Index])(implicit ec: ExecutionContext): Seq[Future[Index]]
  def getValues(index: Index, key: Any, warning: String): Values[Product] //m
  def nonEmpty(index: Index, key: Any): Boolean //m
  def removingDiff(pos: Int, index: Index, keys: Iterable[Any]): Iterable[DOut]
  def partition(currentIndex: Index, diffIndex: Index, key: Any, warning: String): Array[MultiForPart]  //m
  def mayBePar[V](seq: Seq[V]): DPIterable[V]
  //
  def aggregate(values: Iterable[DOut]): AggrDOut
  def buildIndex(data: Seq[AggrDOut])(implicit ec: ExecutionContext): Seq[Future[Index]]
  def keyIteration(seq: Seq[Index])(implicit ec: ExecutionContext): KeyIteration
  def countResults(data: Seq[AggrDOut]): ProfilingCounts
  //
  def getInstantly(future: Index): Index
  //
  def createOutFactory(pos: Int, dir: Int): OutFactory[Any,Product]
  //
  def getValue(dOut: DOut): Product
  def addNS(key: AssembledKey, ns: String): AssembledKey
  //
  def getNonSingles(index: Index, key: Any): Seq[(Product,Int)]
}

// ${outKeyName.fold("DOut=>Unit")(_=>"Tuple2[Any,Product]=>Unit")}      ${outKeyName.fold("buffer.add _")(_=>"pair=>buffer.add(outFactory.result(pair))")}  MutableDOutBuffer

case class ProfilingCounts(callCount: Long, resultCount: Long)

trait MutableDOutBuffer {
  def add(values: Iterable[DOut]): Unit
  def add[K,V<:Product](outFactory: OutFactory[K,V], values: Seq[(K,V)]): Unit
}
trait KeyIterationHandler {
  def outCount: Int
  def handle(id: Any, buffer: MutableDOutBuffer): Unit
}
trait KeyIteration {
  def execute(inner: KeyIterationHandler)(implicit ec: ExecutionContext): Seq[Future[AggrDOut]]
}

trait OutFactory[K,V<:Product] {
  def result(key: K, value: V): DOut
  def result(pair: (K,V)): DOut
}

trait OuterExecutionContext {
  def value: ExecutionContext
  def threadCount: Long
}

trait AggrDOut

trait DOut

object Types {
  type DiffIndexRawSeq = Seq[Index]
  type Outs = Seq[DOut]
  type Values[V] = Seq[V]
  type Each[V] = V
  type DMap[K,V] = Map[K,V] //ParMap[K,V]
  type DPIterable[V] = Iterable[V]
  type Index = RIndex

  private object EmptyReadModel extends ReadModelImpl(emptyDMap)
  //
  def emptyDMap[K,V]: DMap[K,V] = Map.empty
  def emptyReadModel: ReadModel = EmptyReadModel
  def emptyIndex: Index = EmptyRIndex
  //
  type ProfilingLog = List[Product]
  //
  implicit val canCallToValues: CanCallToValues = new CanCallToValues
  implicit val canCallToEach: CanCallToEach = new CanCallToEach
}

trait ReadModelUtil {
  type MMap = DMap[AssembledKey, Index]
  def create(inner: MMap): ReadModel
  def updated(worldKeys: Seq[AssembledKey], values: Seq[Index]): ReadModel=>ReadModel
  def isEmpty: ReadModel=>Boolean
  def op(op: (MMap,MMap)=>MMap): (ReadModel,ReadModel)=>ReadModel
  def toMap: ReadModel=>Map[AssembledKey,Index]
}

trait ReadModel {
  def getIndex(key: AssembledKey): Option[Index]
}

trait Getter[C,+I] {
  def of: C => I
}

object OrEmptyIndex {
  def apply(opt: Option[Index]): Index = opt.getOrElse(emptyIndex)
}
abstract class AssembledKey extends Product {
  def of(model: ReadModel): Index = OrEmptyIndex(model.getIndex(this))
}
trait WorldPartExpression extends WorldPartRule {
  def transform(transition: WorldTransition): WorldTransition
}
//object WorldTransition { type Diff = Map[AssembledKey[_],IndexDiff[Object,_]] } //Map[AssembledKey[_],Index[Object,_]] //Map[AssembledKey[_],Map[Object,Boolean]]
class WorldTransition(
  val prev: Option[WorldTransition],
  val diff: ReadModel,
  val result: ReadModel,
  val profiling: JoiningProfiling,
  val log: ProfilingLog,
  val executionContext: OuterExecutionContext,
  val taskLog: List[AssembledKey]
)

trait JoiningProfiling extends Product {
  type Res = Long
  def time: Long
  def handle(join: Join, result: Seq[AggrDOut], wasLog: ProfilingLog): ProfilingLog
  def handle(join: Join, stage: Long, start: Long, wasLog: ProfilingLog): ProfilingLog
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
  def outputWorldKeys: Seq[AssembledKey]
}

trait DataDependencyProvider {
  def getRules: List[WorldPartRule]
}

abstract class Join(
  val assembleName: String,
  val name: String,
  val inputWorldKeys: Seq[AssembledKey],
  val outputWorldKeys: Seq[AssembledKey],
) extends DataDependencyFrom[Index]
  with DataDependencyTo[Index]
{
  def joins(diffIndexRawSeq: DiffIndexRawSeq, executionContext: OuterExecutionContext): TransJoin
}
trait TransJoin {
  def dirJoin(dir: Int, indexRawSeq: Seq[Index]): Seq[Future[AggrDOut]]
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

trait GeneralExpressionsDumper
trait ExpressionsDumper[To] extends GeneralExpressionsDumper {
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

/*
we declare, that products has fast x.hashCode and ToPrimaryKey(x),
  so no extra memory is needed to cache this information
*/

trait StartUpSpaceProfiler {
  def out(readModelA: ReadModel): Unit
}

trait MemoryOptimizing {
  def indexPower: Int
}
