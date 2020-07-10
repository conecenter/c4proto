
package ee.cone.c4assemble

// see Topological sorting

import Types._
import ee.cone.c4assemble.IndexTypes.{DMultiSet, InnerIndex, InnerKey, Products}
import ee.cone.c4assemble.Merge.Compose
import ee.cone.c4di.{c4, c4multi}

import scala.collection.{immutable, mutable}
import scala.collection.immutable.{Map, Seq, TreeMap}
import scala.util.{Failure, Success}
//import scala.collection.parallel.immutable.ParVector
import scala.concurrent.{ExecutionContext, Future}

case class Count(item: Product, count: Int)

case class DValuesImpl(asMultiSet: DMultiSet, warning: String) extends Values[Product] {
  def length: Int = asMultiSet.size
  private def value(kv: (InnerKey,Products)): Product =
    IndexUtilImpl.single(kv._2,warning)
  def apply(idx: Int): Product = value(asMultiSet.view.slice(idx,idx+1).head)
  def iterator: Iterator[Product] = asMultiSet.iterator.map(value)
  override def isEmpty: Boolean = asMultiSet.isEmpty //optim
}

final  class IndexImpl(val data: InnerIndex) extends Index {
  override def toString: String = s"IndexImpl($data)"
}

object IndexTypes {
  type Products = List[Count]
  type InnerKey = (String,Int)
  type DMultiSet = Map[InnerKey,Products]
  type InnerIndex = DMap[Any,DMultiSet]
}

// todo All on upd, Tree on upd

object IndexUtilImpl {
  def single(items: Products, warning: String): Product =
    if(items.tail.isEmpty && items.head.count==1) items.head.item else {
      val distinct = items.distinct
      if(distinct.tail.nonEmpty) throw new Exception(s"non-single $warning")
      if(warning.nonEmpty) {
        val item = distinct.head.item
        println(s"non-single $warning ${item.productPrefix}:${ToPrimaryKey(item)}")
      }
      distinct.head.item
    }

  def toOrdered(inner: Compose[DMultiSet]): Compose[DMultiSet] =  // Ordering.by can drop keys!: https://github.com/scala/bug/issues/8674
    (a, b) => toOrdered(inner(a, b))
  def toOrdered(res: DMultiSet): DMultiSet =
    if(res.size > 1 && !res.isInstanceOf[TreeMap[_, _]])
    TreeMap.empty[InnerKey,Products] ++ res else res

  def inverse(a: Count): Count = a.copy(count = -a.count)

  def mergeProduct(a: Count, l: Products): Products =
    if(l.isEmpty) a::Nil else {
      val b = l.head
      if(a.item!=b.item) b :: mergeProduct(a,l.tail) else {
        val count = a.count + b.count
        if(count==0) l.tail else b.copy(count=count) :: l.tail
      }
    }

  def mergeProducts(aList: Products, bList: Products): Products =
    if(aList.isEmpty) bList
    else mergeProducts(aList.tail, mergeProduct(aList.head, bList))

  def makeIndex(data: InnerIndex): Index =
    if(data.isEmpty) emptyIndex else new IndexImpl(data)
}

case class JoinKeyImpl(
  was: Boolean, keyAlias: String, keyClassName: String, valueClassName: String
) extends JoinKey {
  override def toString: String =
    s"JK(${if (was) "@was " else ""}@by[$keyAlias] $valueClassName)"
  def withWas(was: Boolean): JoinKey = copy(was=was)
}

final case class ParallelExecution(power: Int) {
  val parallelPartCount: Int = 1 << power
  def keyToPartPos(elem: Any): Int = elem.hashCode & (parallelPartCount-1)
  private val parallelRange = (0 until parallelPartCount).toVector
  def execute[T](f: Int=>T)(implicit ec: ExecutionContext): Future[Vector[T]] =
    Future.sequence(parallelRange.map(partId=>Future(f(partId))))
}

@c4("AssembleApp") final case class IndexUtilImpl()(
  mergeIndexInner: Compose[InnerIndex] =
    Merge[Any,DMultiSet](v=>v.isEmpty,
      IndexUtilImpl.toOrdered(
        Merge[InnerKey,Products](v=>v.isEmpty, IndexUtilImpl.mergeProducts)
      )
    )
) extends IndexUtil {

  def joinKey(was: Boolean, keyAlias: String, keyClassName: String, valueClassName: String): JoinKey =
    JoinKeyImpl(was,keyAlias,keyClassName,valueClassName)

  def data(index: Index): InnerIndex = index match {
    case i: IndexImpl => i.data
    case i if i == emptyIndex => Map.empty
  }

  def getMS(index: Index, key: Any): Option[DMultiSet] = index match {
    case i: IndexImpl => i.data.get(key)
    case _ if index == emptyIndex => None
  }

  def isEmpty(index: Index): Boolean = data(index).isEmpty

  def keySet(index: Index): Set[Any] = data(index).keySet

  def nonEmpty(index: Index, key: Any): Boolean = {
    val res = getMS(index,key).nonEmpty
    res
  }

  def getValues(index: Index, key: Any, warning: String): Values[Product] = {
    val res = getMS(index,key).fold(Nil:Values[Product])(v => DValuesImpl(v,warning))
    res
  }


  def mergeIndex(l: DPIterable[Index]): Index = {
    val res =
      IndexUtilImpl.makeIndex(l.map(data).foldLeft(Map.empty: InnerIndex)(mergeIndexInner))
    res
  }

  def zipMergeIndex(aDiffs: Seq[Index])(bDiffs: Seq[Index]): Seq[Index] = {
    assert(aDiffs.size == bDiffs.size)
    (aDiffs zip bDiffs).map{ case (a,b) => mergeIndex(Seq(a, b)) }
  }

  def emptyMS: DMultiSet = Map.empty

  def removingDiff(index: Index, keys: Iterable[Any]): Index = {
    val pairs = for {
      key <- keys
      values <- getMS(index,key)
    } yield key->values.transform((k,v)=> v.map(IndexUtilImpl.inverse))
    IndexUtilImpl.makeIndex(pairs.toMap)
  }

  def partition(currentIndex: Index, diffIndex: Index, key: Any, warning: String): List[MultiForPart] = {
    getMS(currentIndex,key).fold(Nil:List[MultiForPart]){currentValues =>
      val diffValues = getMS(diffIndex,key).getOrElse(emptyMS)
      if(currentValues eq diffValues){
        val changed = (for {
          (k,values) <- currentValues
        } yield IndexUtilImpl.single(values,warning)).toList
        new ChangedMultiForPart(changed) :: Nil
      } else {
        val changed = (for {
          (k,v) <- diffValues; values <- currentValues.get(k)
        } yield IndexUtilImpl.single(values,warning)).toList
        val unchanged: ()=>List[Product] = ()=>(for {
          (k,values) <- currentValues if !diffValues.contains(k)
        } yield IndexUtilImpl.single(values,warning)).toList
        val unchangedRes = new UnchangedMultiForPart(unchanged) :: Nil
        if(changed.nonEmpty) new ChangedMultiForPart(changed) :: unchangedRes else unchangedRes
      }
    }
  }

  def mayBePar[V](seq: immutable.Seq[V]): DPIterable[V] = seq

  def aggregate(values: Iterable[DOut]): AggrDOut = {
    val setup = IndexBuildSetup(1)
    val buffer = setup.createBuffer()
    buffer.add(values)
    buffer.result
  }
  def keyIteration(seq: Seq[Index]): KeyIteration    = {
    val parallelExecution = ParallelExecution(5) //32
    val buffer = new MutableGroupingBufferImpl[Any](parallelExecution.parallelPartCount)
    seq.foreach(index=>data(index).keySet.foreach(key=>buffer.add(parallelExecution.keyToPartPos(key),key)))
    val groups = buffer.toVector.map(_.distinct)
    new KeyIterationImpl(parallelExecution, groups)
  }
  def buildIndex(data: Seq[AggrDOut])(implicit ec: ExecutionContext): Seq[Future[Index]] = {
    assert(data.nonEmpty)
    val dataI = data.asInstanceOf[Seq[AggrDOutImpl]]
    val setup = Single(dataI.map(_.setup).distinct)
    (0 until setup.outCount).map{ outPos =>
      setup.parallelExecution.execute{ partPos =>
        val index: mutable.Map[Any,DMultiSet] = mutable.Map()
        dataI.foreach{ aggr =>
          aggr.byOutThenTarget(setup.bufferPos(outPos, partPos)).foreach{ rec =>
            val key = rec.key
            val mKey = rec.mKey
            val wasValues = index.getOrElse(key,Map.empty)
            val wasCounts = wasValues.getOrElse(mKey,Nil)
            val counts = IndexUtilImpl.mergeProduct(rec.count,wasCounts)
            val values: DMultiSet = IndexUtilImpl.toOrdered(if(counts.nonEmpty) wasValues.updated(mKey,counts) else wasValues.removed(mKey))
            if(values.nonEmpty) index(key) = values else assert(index.remove(key).nonEmpty)
          }
        }
        index.toMap
      }.map{ pRes: Seq[InnerIndex] =>
        IndexUtilImpl.makeIndex(pRes.reduce((a,b)=>a++b))
      }
    }
  }
  def countResults(data: Seq[AggrDOut]): ProfilingCounts =
    data.asInstanceOf[Seq[AggrDOutImpl]]
      .foldLeft(ProfilingCounts(0L,0L))((res,aggr) => res.copy(
        callCount = res.callCount + aggr.profilingCounts.callCount,
        resultCount = res.resultCount + aggr.profilingCounts.resultCount,
      ))

  def createOutFactory(pos: Int, dir: Int): OutFactory[Any, Product] =
    new OutFactoryImpl(pos,dir)

  @SuppressWarnings(Array("org.wartremover.warts.TryPartial")) def getInstantly(future: Future[Index]): Index = future.value.get.get
}

final class ChangedMultiForPart(val items: List[Product]) extends MultiForPart {
  def isChanged: Boolean = true
}
final class UnchangedMultiForPart(getItems: ()=>List[Product]) extends MultiForPart {
  def isChanged: Boolean = true
  lazy val items: List[Product] = getItems()
}

final class MutableGroupingBufferImpl[T](count: Int) {
  private val buffers: Array[List[T]] = Array.fill(count)(Nil)
  def add(pos: Int, value: T): Unit = { buffers(pos) = value :: buffers(pos) }
  def toVector: Vector[Seq[T]] = buffers.toVector
}

/*
final class MutableGroupingBufferImpl[T](count: Int) {
  private val buffers = Vector.fill(count)(new mutable.ArrayBuffer[T])
  def add(pos: Int, value: T): Unit = { val _ = buffers(pos).addOne(value) }
  def toVector: Vector[Vector[T]] = buffers.map(_.toVector)
}
*/

final case class IndexBuildSetup(outCount: Int){
  val parallelExecution: ParallelExecution = ParallelExecution(2)
  def bufferCount: Int = outCount * parallelExecution.parallelPartCount
  def bufferPos(outPos: Int, key: Any): Int = outPos * parallelExecution.parallelPartCount + parallelExecution.keyToPartPos(key)
  def createBuffer(): DOutAggregationBuffer = new DOutAggregationBuffer(this)
}
final class AggrDOutImpl(val setup: IndexBuildSetup, val byOutThenTarget: Vector[Seq[DOutImpl]], val profilingCounts: ProfilingCounts) extends AggrDOut
final class DOutAggregationBuffer(setup: IndexBuildSetup) extends MutableDOutBuffer {
  private val inner = new MutableGroupingBufferImpl[DOutImpl](setup.bufferCount)
  private val callCounter = Array[Long](0L,0L)
  private val addOne: DOut=>Unit = { case v: DOutImpl =>
    inner.add(setup.bufferPos(v.pos,v.key), v)
    callCounter(1) += 1
  }
  def add(values: Iterable[DOut]): Unit = {
    callCounter(0) += 1
    values.foreach(addOne)
  }
  def add[K,V<:Product](outFactory: OutFactory[K,V], values: Seq[(K,V)]): Unit = {
    callCounter(0) += 1
    values.foreach(pair => addOne(outFactory.result(pair)))
  }
  def result: AggrDOutImpl = new AggrDOutImpl(setup, inner.toVector, ProfilingCounts(callCounter(0),callCounter(1)))
}
//final class DOutAggregation(){
//
//  def createBuffer() = new DOutAggregationBuffer
//}


final class KeyIterationImpl(parallelExecution: ParallelExecution, parts: Vector[Seq[Any]]) extends KeyIteration {
  def execute(inner: KeyIterationHandler)(implicit ec: ExecutionContext): Future[Seq[AggrDOut]] = {
    val setup = IndexBuildSetup(inner.outCount)
    parallelExecution.execute{ partId =>
      val buffer = setup.createBuffer()
      parts(partId).foreach(key=>inner.handle(key,buffer))
      buffer.result
    }
  }
}

// makeIndex(Map(key->Map((ToPrimaryKey(product),product.hashCode)->(Count(product,count)::Nil)))/*, opt*/)
final class DOutImpl(val pos: Int, val key: Any, val mKey: InnerKey, val count: Count) extends DOut
final class OutFactoryImpl(pos: Int, dir: Int) extends OutFactory[Any, Product] {
  def result(key: Any, value: Product): DOut =
    new DOutImpl(pos,key,(ToPrimaryKey(value),value.hashCode),Count(value,dir))
  def result(pair: (Any, Product)): DOut = {
    val (k,v) = pair
    result(k,v)
  }
}

////////////////////////////////////////////////////////////////////////////////

@c4("AssembleApp") final class IndexFactoryImpl(
  val util: IndexUtil, factory: JoinMapIndexFactory
) extends IndexFactory {
  def createJoinMapIndex(join: Join):
    WorldPartExpression
      with DataDependencyFrom[Index]
      with DataDependencyTo[Index]
  = factory.create(join)
}

/*
trait ParallelAssembleStrategy {

}*/

@c4multi("AssembleApp") final class JoinMapIndex(join: Join)(
  updater: IndexUpdater,
  composes: IndexUtil
) extends WorldPartExpression
  with DataDependencyFrom[Index]
  with DataDependencyTo[Index]
{
  def assembleName = join.assembleName
  def name = join.name
  def inputWorldKeys: Seq[AssembledKey] = join.inputWorldKeys
  def outputWorldKeys: Seq[AssembledKey] = join.outputWorldKeys

  override def toString: String = s"${super.toString} \n($assembleName,$name,\nInput keys:\n${inputWorldKeys.mkString("\t\n")},\nOutput keys:$outputWorldKeys)"

  def transform(transition: WorldTransition): WorldTransition = {
    val worldDiffOpts: Seq[Option[Future[Index]]] = inputWorldKeys.map(transition.diff.getFuture)
    if(worldDiffOpts.forall(_.isEmpty)) transition
    else doTransform(transition, worldDiffOpts)
  }
  def doTransform(transition: WorldTransition, worldDiffOpts: Seq[Option[Future[Index]]]): WorldTransition = {
    implicit val executionContext: ExecutionContext = transition.executionContext.value
    def getNoUpdates(log: ProfilingLog): Future[IndexUpdates] = for {
      outputDiffs <- Future.sequence(outputWorldKeys.map(_.of(transition.diff)))
      outputResults <- Future.sequence(outputWorldKeys.map(_.of(transition.result)))
    } yield new IndexUpdates(outputDiffs,outputResults,log)
    val next: Future[IndexUpdates] = for {
      worldDiffs <- Future.sequence(worldDiffOpts.map(OrEmptyIndex(_)))
      res <- {
        if (worldDiffs.forall(composes.isEmpty)) getNoUpdates(Nil)
        else for {
          prevInputs <- Future.sequence(inputWorldKeys.map(_.of(transition.prev.get.result)))
          inputs <- Future.sequence(inputWorldKeys.map(_.of(transition.result)))
          profiler = transition.profiling
          calcStart = profiler.time
          runJoin = join.joins(worldDiffs, transition.executionContext)
          joinResSeq <- Future.sequence(Seq(runJoin.dirJoin(-1,prevInputs), runJoin.dirJoin(+1,inputs)))
          joinRes = joinResSeq.flatten
          calcLog = profiler.handle(join, 0L, calcStart, Nil)
          countLog = profiler.handle(join, joinRes, calcLog)
          findChangesStart = profiler.time
          indexDiffs <- Future.sequence(composes.buildIndex(joinRes))
          findChangesLog = profiler.handle(join, 1L, findChangesStart, countLog)
          noUpdates <- getNoUpdates(findChangesLog)
        } yield {
          val patchStart = profiler.time
          val diffs = composes.zipMergeIndex(noUpdates.diffs)(indexDiffs)
          val results = composes.zipMergeIndex(noUpdates.results)(indexDiffs)
          val patchLog = profiler.handle(join, 2L, patchStart, findChangesLog)
          new IndexUpdates(diffs, results, patchLog)
        }
      }
    } yield res
    updater.setPart(outputWorldKeys,next,logTask = true)(transition)
  }
}

/* For debug purposes
class DebugIndexFactoryImpl(
  val util: IndexUtil,
  updater: IndexUpdater,
  readModelUtil: ReadModelUtil
) extends IndexFactory {
  def createJoinMapIndex(join: Join):
  WorldPartExpression
    with DataDependencyFrom[Index]
    with DataDependencyTo[Index]
  = new DebugJoinMapIndex(join, updater, util, readModelUtil)
}

class DebugJoinMapIndex(
  join: Join,
  updater: IndexUpdater,
  composes: IndexUtil,
  readModelUtil: ReadModelUtil
) extends WorldPartExpression
  with DataDependencyFrom[Index]
  with DataDependencyTo[Index]
{
  def assembleName = join.assembleName
  def name = join.name
  def inputWorldKeys: Seq[AssembledKey] = join.inputWorldKeys
  def outputWorldKey: AssembledKey = join.outputWorldKey

  override def toString: String = s"${super.toString} \n($assembleName,$name,\nInput keys:\n${inputWorldKeys.mkString("\t\n")},\nOutput key:$outputWorldKey)"

  def transform(transition: WorldTransition): WorldTransition = {

    val next: Future[IndexUpdate] = for {
      worldDiffs <- Future.sequence(inputWorldKeys.map(_.of(transition.diff)))
      res <- {
        if (worldDiffs.forall(composes.isEmpty)) for {
          outputDiff <- outputWorldKey.of(transition.diff)
          outputData <- outputWorldKey.of(transition.result)
        } yield new IndexUpdate(outputDiff,outputData,Nil)
        else for {
          prevInputs <- Future.sequence(inputWorldKeys.map(_.of(transition.prev.get.result)))
          inputs <- Future.sequence(inputWorldKeys.map(_.of(transition.result)))
          profiler = transition.profiling
          calcStart = profiler.time
          joinRes = join.joins(Seq(-1->prevInputs, +1->inputs).par, worldDiffs)
          calcLog = profiler.handle(join, 0L, calcStart, joinRes, Nil)
          findChangesStart = profiler.time
          indexDiff = composes.mergeIndex(joinRes)
          findChangesLog = profiler.handle(join, 1L, findChangesStart, Nil, calcLog)
          outputDiff <- outputWorldKey.of(transition.diff)
          outputData <- outputWorldKey.of(transition.result)
        } yield {
          if(composes.isEmpty(indexDiff))
            new IndexUpdate(outputDiff,outputData,findChangesLog)
          else {
            val patchStart = profiler.time
            val nextDiff = composes.mergeIndex(Seq(outputDiff, indexDiff))
            val nextResult = composes.mergeIndex(Seq(outputData, indexDiff))
            val patchLog = profiler.handle(join, 2L, patchStart, Nil, findChangesLog)
            new IndexUpdate(nextDiff,nextResult,patchLog)
          }
        }
      }
    } yield res
    testTransition(updater.setPart(outputWorldKey)(next)(transition))
  }

  def testTransition(transition: WorldTransition): WorldTransition = {
    val readModelDone = Await.result(readModelUtil.ready(transition.result), Duration.Inf)
    readModelDone match {
      case a: ReadModelImpl =>
        (for {
          (assKey, indexF) <- a.inner
        } yield {
          indexF.map {
            case index: IndexImpl =>
              for {
                (outerKey, values) <- index.data
                (pk, counts) <- values
                count <- counts
              } yield {
                assert(count.count >= 0, s"Failed ${count.count} at assKey:$assKey, outerKey:$outerKey, pk:$pk after join $name/$assembleName")
                0
              }
            case _ => 0
          }
        }).map(Await.result(_, Duration.Inf))
      case _ => 0
    }
    transition
  }
}
*/

class FailedRule(val message: List[String]) extends WorldPartRule

@c4("AssembleApp") final class TreeAssemblerImpl(
  byPriority: ByPriority, expressionsDumpers: List[ExpressionsDumper[Unit]],
  optimizer: AssembleSeqOptimizer, backStageFactory: BackStageFactory,
  replaceImplFactory: ReplaceImplFactory
) extends TreeAssembler {
  def create(rules: List[WorldPartRule], isTarget: WorldPartRule=>Boolean): Replace = {
    type RuleByOutput = Map[AssembledKey, Seq[WorldPartRule]]
    val uses: RuleByOutput = (for{
      e <- rules.collect{ case e: DataDependencyTo[_] => e }
      outputWorldKey <- e.outputWorldKeys
    } yield outputWorldKey -> e).groupMap(_._1)(_._2)
    // rules.collect{ case e: DataDependencyTo[_] => e }.flatMap().groupBy(_.outputWorldKey)
    for {
      (key,rules) <- uses
      _:OriginalWorldPart[_] <- rules
    } assert(rules.size <= 1, s"can not output to original: $key")
    //
    val rulesByPriority: List[WorldPartRule] = {
      val getJoins: WorldPartRule => List[WorldPartRule] = rule => for {
        join <- List(rule).collect{ case j: DataDependencyFrom[_] => j }
        inKey <- join.inputWorldKeys
        k <- uses.getOrElse(inKey,inKey match {
          case k: JoinKey if k.was => Nil
          case k => List(new FailedRule(List(
            s"$k not found",
            s"for assemble ${join.assembleName}, join ${join.name}"
          )))
        })
      } yield k
      byPriority.byPriority[WorldPartRule,WorldPartRule](
        item=>(getJoins(item), _ => item)
      )(rules.filter(isTarget)).reverse
    }
    rulesByPriority.collect{ case r: FailedRule => r } match {
      case Seq() => ()
      case rules =>
        val lines = s"${rules.size} rules have failed" :: rules.flatMap(_.message)
        throw new Exception(lines.mkString("\n"))
    }
    val expressionsByPriority = rulesByPriority.collect{
      case e: WorldPartExpression with DataDependencyTo[_] with DataDependencyFrom[_] => e
    }
    expressionsDumpers.foreach(_.dump(expressionsByPriority))
    val expressionsByPriorityWithLoops = optimizer.optimize(expressionsByPriority)
    val backStage =
      backStageFactory.create(expressionsByPriorityWithLoops.collect{ case e: WorldPartExpression with DataDependencyFrom[_] => e })
    val transforms = expressionsByPriorityWithLoops ::: backStage ::: Nil
    val transformAllOnce: WorldTransition=>WorldTransition = Function.chain(transforms.map(h=>h.transform(_)))
    replaceImplFactory.create(rulesByPriority, transformAllOnce)
  }
}

@c4multi("AssembleApp") final class ReplaceImpl(
  val active: List[WorldPartRule],
  transformAllOnce: Transform[WorldTransition]
)(
  composes: IndexUtil, readModelUtil: ReadModelUtil,
) extends Replace {
  def transformUntilStable(left: Int, transition: WorldTransition): Future[WorldTransition] = {
    implicit val executionContext: ExecutionContext = transition.executionContext.value
    for {
      stable <- readModelUtil.isEmpty(executionContext)(transition.diff) //seq
      res <- {
        if(stable) Future.successful(transition)
        else if(left > 0) transformUntilStable(left-1, {
          //val start = System.nanoTime
          val r = transformAllOnce(transition)
          //println(s"transformAllOnce ${r.taskLog.size} (${r.taskLog.takeRight(4)})/${transforms.size} rules \n${(System.nanoTime-start)/1000000} ms")
          r
        })
        else Future.failed(new Exception(s"unstable assemble ${transition.diff}"))
      }
    } yield res
  }
  def replace(
    prevWorld: ReadModel,
    diff: ReadModel,
    profiler: JoiningProfiling,
    executionContext: OuterExecutionContext
  ): Future[WorldTransition] = {
    implicit val ec = executionContext.value
    val prevTransition = WorldTransition(None,emptyReadModel,prevWorld,profiler,Future.successful(Nil),executionContext,Nil)
    val currentWorld = readModelUtil.op(Merge[AssembledKey,Future[Index]](_=>false/*composes.isEmpty*/,(a,b)=>for {
      seq <- Future.sequence(Seq(a,b))
    } yield composes.mergeIndex(seq) ))(prevWorld,diff)
    val nextTransition = WorldTransition(Option(prevTransition),diff,currentWorld,profiler,Future.successful(Nil),executionContext,Nil)
    for {
      finalTransition <- transformUntilStable(1000, nextTransition)
      ready <- readModelUtil.ready(ec)(finalTransition.result) //seq
    } yield finalTransition
  }
}

object UMLExpressionsDumper extends ExpressionsDumper[String] {
  def dump(expressions: List[DataDependencyTo[_] with DataDependencyFrom[_]]): String = {
    val keyAliases: List[(AssembledKey, String)] =
      expressions.flatMap(e => e.outputWorldKeys.toList ::: e.inputWorldKeys.toList)
        .distinct.zipWithIndex.map{ case (k,i) => (k,s"wk$i")}
    val keyToAlias: Map[AssembledKey, String] = keyAliases.toMap
    List(
      for((k:Product,a) <- keyAliases) yield
        s"(${k.productElement(0)} ${k.productElement(2).toString.split("[\\$\\.]").last}) as $a",
      for((e,eIndex) <- expressions.zipWithIndex; k <- e.inputWorldKeys)
        yield s"${keyToAlias(k)} --> $eIndex-${e.name}",
      for((e,eIndex) <- expressions.zipWithIndex; k <- e.outputWorldKeys)
        yield s"$eIndex-${e.name} --> ${keyToAlias(k)}"
    ).flatten.mkString("@startuml\n","\n","\n@enduml")
  }
}

@c4("AssembleApp") final class AssembleDataDependencyFactoryImpl(indexFactory: IndexFactory) extends AssembleDataDependencyFactory {
  def create(assembles: List[Assemble]): List[WorldPartRule] = {
    def gather(assembles: List[Assemble]): List[Assemble] =
      if(assembles.isEmpty) Nil
      else gather(assembles.collect{ case a: CallerAssemble => a.subAssembles }.flatten) ::: assembles
    val(was,res) = gather(assembles).foldLeft((Set.empty[String],List.empty[Assemble])){(st,add)=>
      val(was,res) = st
      add match {
        case m: MergeableAssemble if was(m.mergeKey) => (was,res)
        case m: MergeableAssemble => (was+m.mergeKey,m::res)
        case m => (was,m::res)
      }
    }
    res.flatMap(_.dataDependencies(indexFactory))
  }
}

@c4("AssembleApp") final class AssembleDataDependencies(
  factory: AssembleDataDependencyFactory, assembles: List[Assemble]
) extends DataDependencyProvider {
  def getRules: List[WorldPartRule] = factory.create(assembles)
}

object Merge {
  type Compose[V] = (V,V)=>V
  def bigFirst[K,V](inner: Compose[DMap[K,V]]): Compose[DMap[K,V]] =
    (a,b) => {
      if(a.size < b.size) inner(b,a) else inner(a,b)
    }
  def apply[K,V](isEmpty: V=>Boolean, inner: Compose[V]): Compose[DMap[K,V]] =
    bigFirst((bigMap,smallMap) => {
      val res =
      smallMap.foldLeft(bigMap){ (resMap,kv)=>
        val (k,smallVal) = kv
        val bigValOpt = resMap.get(k)
        val resVal = if(bigValOpt.isEmpty) smallVal else inner(bigValOpt.get,smallVal)
        if(isEmpty(resVal)) resMap - k else resMap + (k -> resVal)
      }
      res
    })
}

// if we need more: scala rrb vector, java...binarySearch
// also consider: http://docs.scala-lang.org/overviews/collections/performance-characteristics.html


////////////////////////////////////////////////////////////////////////////////

//class NonSingleValuesException(k: Product, v: Int) extends Exception

//trait IndexOpt extends Product
//case object UndefinedIndexOpt extends IndexOpt
//case class DefIndexOpt(key: AssembledKey) extends IndexOpt

/*
trait DValues extends Product {
  def setOnDistinct(onDistinct: Count=>Unit): Seq[Product]
}

case class SingleValues(item: Product) extends DValues with Seq[Product] {
  def setOnDistinct(onDistinct: Count=>Unit): Seq[Product] = this

  def length: Int = 1
  def apply(idx: Int): Product =
    if(idx==0) item else throw new IndexOutOfBoundsException
  def iterator: Iterator[Product] = Iterator(item)
}*/

//type DPMap[K,V] = GenMap[K,V] //ParMap[K,V]
//
/*



  val wrapIndex: Object => Any => Option[DMultiSet] = in => {
    val index = in.asInstanceOf[Index]
    val all = index.get(All)
    if(all.nonEmpty) (k:Any)=>all else (k:Any)=>index.get(k)
  },
  val wrapValues: Option[DMultiSet] => Values[Product] =
    _.fold(Nil:Values[Product])(m=>DValuesImpl(m.asInstanceOf[TreeMap[PreHashed[Product],Int]])),
  val mergeIndex: Compose[Index] = Merge[Any,DMultiSet](_.isEmpty,Merge(_==0,_+_)),
  val diffFromJoinRes: DPIterable[JoinRes]=>Option[Index] = {
    def valuesDiffFromJoinRes(in: DPIterable[JoinRes]): Option[DMultiSet] = {
      val m = in.foldLeft(emptyMultiSet) { (res, jRes) =>
        val k = jRes.productHashed
        val was = res.getOrElse(k,0)
        val will = was + jRes.count
        if(will==0) res - k else res + (k->will)
      }
      if(m.isEmpty) None else Option(m.seq)
    }
    in =>
      val m = for {(k,part) <- in.groupBy(_.byKey); v <- valuesDiffFromJoinRes(part) } yield k->v
      if(m.isEmpty) None else Option(m.seq.toMap)
  }
  def partition(currentOpt: Option[DMultiSet], diffOpt: Option[DMultiSet]): Iterable[(Boolean,GenIterable[PreHashed[Product]])] =


  def keySet(indexSeq: Seq[Index]): Set[Any] = indexSeq.map(_.keySet).reduce(_++_)
  def result(key: Any, product: Product, count: Int): JoinRes =
    new JoinRes(key,preHashing.wrap(product),count)
*/


/******************************************************************************/

/*
object IndexFactoryUtil {
  def group[K,V](by: JoinRes=>K, wrap: DPMap[K,V]=>DMap[K,V], inner: DPIterable[JoinRes] => Option[V]): DPIterable[JoinRes] => Option[DMap[K,V]] =
    (in:DPIterable[JoinRes]) => {
      val m = for {(k,part) <- in.groupBy(by); v <- inner(part) } yield k->v
      if(m.isEmpty) None else Option(wrap(m))
    }
  def sumOpt: DPIterable[JoinRes] => Option[Int] = part => {
    val sum = part.map(_.count).sum
    if(sum==0) None else Option(sum)
  }
}
val diffFromJoinRes: DPIterable[JoinRes]=>Option[Index] =
    IndexFactoryUtil.group[Any,DMultiSet](_.byKey, _.seq.toMap,
      IndexFactoryUtil.group[PreHashed[Product],Int](_.productHashed, emptyMultiSet++_, IndexFactoryUtil.sumOpt)
    )

*/

/*
object IndexFactoryUtil {
  def group[K,V](by: JoinRes=>K, empty: V, isEmpty: V=>Boolean, inner: (V,JoinRes)=>V): (DMap[K,V],JoinRes)=>DMap[K,V] =
    (res,jRes) => {
      val k = by(jRes)
      val was = res.getOrElse(k,empty)
      val will = inner(was,jRes)
      if(isEmpty(will)) res - k else res + (k->will)
    }
}
val diffFromJoinRes: DPIterable[JoinRes]=>Option[Index] =
    ((in:DPIterable[JoinRes])=>in.foldLeft(emptyIndex)(
      IndexFactoryUtil.group[Any,DMultiSet](_.byKey, emptyMultiSet, _.isEmpty,
        IndexFactoryUtil.group[PreHashed[Product],Int](_.productHashed, 0, _==0,
          (res,jRes)=>res+jRes.count
        )
      )
    )).andThen(in=>Option(in).filter(_.nonEmpty))
*/


