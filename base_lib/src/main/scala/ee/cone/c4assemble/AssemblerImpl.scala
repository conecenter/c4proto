
package ee.cone.c4assemble

// see Topological sorting

import java.nio.file.{Files, Path, Paths}
import Types._
import ee.cone.c4assemble.IndexTypes.{Count, Products}
import ee.cone.c4assemble.RIndexTypes.{RIndexItem, RIndexKey}
import ee.cone.c4di.{c4, c4multi}

import scala.annotation.tailrec
import scala.collection.immutable
import scala.collection.immutable.{Map, Seq}
import scala.concurrent.{ExecutionContext, Future}
import java.nio.charset.StandardCharsets.UTF_8

class NonSingleCount(val item: Product, val count: Int)
sealed class Counts(val data: List[Count])
object EmptyCounts extends Counts(Nil)

object IndexTypes {
  type Tagged[U] = { type Tag = U }
  type Products = RIndexItem
  sealed trait CountTag
  type Count = Object with Tagged[CountTag]
}

// todo All on upd, Tree on upd

case class JoinKeyImpl(
  was: Boolean, keyAlias: String, keyClassName: String, valueClassName: String
) extends JoinKey {
  override def toString: String =
    s"JK(${if (was) "@was " else ""}@by[$keyAlias] $valueClassName)"
  def withWas(was: Boolean): JoinKey = copy(was=was)
}

final case class ParallelExecution(power: Int) {
  val parallelPartCount: Int = 1 << power
  def keyToPartPos(elem: Any): Int = elem.hashCode & (parallelPartCount-1)  // todo other hash?
  private val parallelRange = (0 until parallelPartCount).toVector
  def execute[T](f: Int=>T)(implicit ec: ExecutionContext): Future[Vector[T]] =
    Future.sequence(parallelRange.map(partId=>Future(f(partId))))
}

// Ordering.by can drop keys!: https://github.com/scala/bug/issues/8674
@c4("AssembleApp") final class IndexUtilImpl(
  rIndexUtil: RIndexUtil,
  memoryOptimizing: MemoryOptimizing,
  noParts: Array[MultiForPart] = Array.empty,
  val isSingle: Products=>Boolean = {
    case _: Counts => false
    case _: NonSingleCount => false
    case _: Product => true
  }
) extends IndexUtil {
  def single(products: Products, warning: String): Product = products match {
    case c: Counts => throw new Exception(s"non-single $c")
    case c: NonSingleCount =>
      if(warning.nonEmpty)
        println(s"non-single $warning ${c.item.productPrefix}:${ToPrimaryKey(c.item)}")
      c.item
    case item: Product => item
  }
  def isEmptyProducts(products: Products): Boolean = products eq emptyCounts
  def emptyCounts: Products = asUProducts(EmptyCounts)
  def asUProducts(count: Count): Products = count.asInstanceOf[Products]
  def asUProducts(counts: Counts): Products = counts.asInstanceOf[Products]
  def asCount(products: Products): Count = products match {
    case m: Counts => throw new Exception(s"non-single $m")
    case c: NonSingleCount => c.asInstanceOf[Count]
    case item: Product => item.asInstanceOf[Count]
  }
  def toCounts(products: Products): List[Count] = products match {
    case m: Counts => m.data //unsorted
    case c => asCount(c) :: Nil
  }
  def getItem(products: Count): Product = products match {
    case c: NonSingleCount => c.item
    case item: Product => item
  }
  def getCount(products: Count): Int = products match {
    case c: NonSingleCount => c.count
    case _: Product => 1
  }
  def mergeProducts(a: Products, b: Products): Products = {
    if(isEmptyProducts(a)) b else if(isEmptyProducts(b)) a
    else if(a.isInstanceOf[Counts] || b.isInstanceOf[Counts]) mergeMulti(a,b)
    else {
      val aCount = asCount(a)
      val bCount = asCount(b)
      val aItem = getItem(aCount)
      if(aItem == getItem(bCount)) {
        val count = getCount(aCount)+getCount(bCount)
        if(count==0) emptyCounts else asUProducts(makeCount(aItem,count))
      }
      else mergeMulti(a,b)
    }
  }
  def mergeMulti(a: Products, b: Products): Products =
    (toCounts(a) ++ toCounts(b))
    .groupMapReduce(getItem)(getCount)(_+_)
    .collect{ case (item,count) if count!=0 => makeCount(item,count) }.toList match {
      case Seq() => emptyCounts
      case Seq(count) => asUProducts(count)
      case s if s.size > 1 => asUProducts(new Counts(s))
    }
  def makeCount(item: Product, count: Int): Count = count match {
    case 0 => throw new Exception
    case 1 => item.asInstanceOf[Count]
    case n => new NonSingleCount(item, n).asInstanceOf[Count]
  }

  def rIndexValueOperations: RIndexValueOperations = new RIndexValueOperations {
    def compare(a: RIndexItem, b: RIndexItem): Int = {
      val aP = headProduct(a)
      val bP = headProduct(b)
      val r = RawToPrimaryKey.get(aP) compareTo RawToPrimaryKey.get(bP)
      if (r == 0) java.lang.Integer.compare(aP.hashCode,bP.hashCode) else r
    }
    def merge(a: RIndexItem, b: RIndexItem): RIndexItem = mergeProducts(a,b)
    def nonEmpty(value: RIndexItem): Boolean = !isEmptyProducts(value)
  }

  //noinspection NoTailRecursionAnnotation
  def headProduct(products: Products): Product = products match {
    case m: Counts => headProduct(asUProducts(m.data.head))
    case c: NonSingleCount => c.item
    case p: Product => p
  }

  def inverse(a: Count): Count = makeCount(getItem(a), -getCount(a))

  def isEmpty(index: Index): Boolean = index eq EmptyRIndex
  def valueCount(index: Index): Int = rIndexUtil.valueCount(index)
  def keyCount(index: Index): Int = rIndexUtil.keyCount(index)
  def keyIterator(index: Index): Iterator[Any] = rIndexUtil.keyIterator(index)

  def oKey(key: Any): RIndexKey = key match { case k: Object => k.asInstanceOf[RIndexKey] }

  def joinKey(was: Boolean, keyAlias: String, keyClassName: String, valueClassName: String): JoinKey =
    JoinKeyImpl(was,keyAlias,keyClassName,valueClassName)

  def nonEmpty(index: Index, key: Any): Boolean =
    rIndexUtil.nonEmpty(index,oKey(key))

  def getValues(index: Index, key: Any, warning: String): Values[Product] = { // gives Vector; todo ? ArraySeq.unsafeWrapArray(
    val values = rIndexUtil.get(index,oKey(key))
    if(values.forall(isSingle)) values.asInstanceOf[Seq[Product]]
    else values.map(single(_,warning))
  }

  def getNonSingles(index: Index, key: Any): Seq[(Product,Int)] =
    rIndexUtil.get(index,oKey(key)).flatMap{
      case m: Counts => m.data.map{
        case c: NonSingleCount => (c.item, c.count)
        case p: Product => (p,1)
      }
      case c: NonSingleCount => (c.item, c.count) :: Nil
      case p: Product => Nil
    }

  def mergeIndex(l: DPIterable[Index]): Index = // size 2
    rIndexUtil.merge(l.toSeq,rIndexValueOperations)

  def zipMergeIndex(aDiffs: Seq[Index])(bDiffs: Seq[Index]): Seq[Index] = {
    assert(aDiffs.size == bDiffs.size)
    (aDiffs zip bDiffs).map{ case (a,b) => mergeIndex(Seq(a, b)) }
  }

  def zipMergeIndex(aDiffs: Seq[Index], bDiffs: Seq[Index])(implicit ec: ExecutionContext): Future[Seq[Index]] = {
    val parallelExecution = ParallelExecution(3)
    assert(aDiffs.size == bDiffs.size)
    val ops = rIndexValueOperations
    Future.sequence((aDiffs zip bDiffs).map{ case (a,b) =>
      val aParts = rIndexUtil.split(a, parallelExecution.parallelPartCount)
      val bParts = rIndexUtil.split(b, parallelExecution.parallelPartCount)
      parallelExecution.execute(partPos=>
        rIndexUtil.merge(Seq(aParts(partPos), bParts(partPos)),ops)
      ).map(rIndexUtil.merge(_,ops))
    })
  }

  def removingDiff(pos: Int, index: Index, keys: Iterable[Any]): Iterable[DOut] =
    for {
      key <- keys
      products  <- rIndexUtil.get(index,oKey(key))
      count <- toCounts(products)
    } yield new DOutImpl(pos, oKey(key), asUProducts(inverse(count)))

  def partition(currentIndex: Index, diffIndex: Index, key: Any, warning: String): Array[MultiForPart] = {
    val currentMS = rIndexUtil.get(currentIndex,oKey(key))
    if(currentMS.isEmpty) noParts else {
      if(rIndexUtil.eqBuckets(currentIndex,diffIndex,oKey(key))){ // todo fix with emb-ing ?
        //MeasureP("partition0",currentMS.size)
        val changed = currentMS.toArray.map(single(_,warning))
        Array(new ChangedMultiForPart(changed))
      } else {
        val diffMS = rIndexUtil.get(diffIndex,oKey(key))
        //MeasureP("partition1",currentMS.size+diffMS.size)
        val changed =
          rIndexUtil.changed(currentMS,diffMS,rIndexValueOperations)
            .map(single(_,warning))
        val unchanged: ()=>Array[Product] = () => {
          rIndexUtil.unchanged(currentMS, diffMS, rIndexValueOperations)
            .map(single(_, warning))
        }
        val unchangedRes = new UnchangedMultiForPart(unchanged)
        if(changed.nonEmpty) Array(new ChangedMultiForPart(changed),unchangedRes) else Array(unchangedRes)
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
    seq.foreach(index=>rIndexUtil.keyIterator(index).foreach{ key =>
      buffer.add(parallelExecution.keyToPartPos(key),key)
    })
    val groups = buffer.toVector.map(_.distinct)
    new KeyIterationImpl(parallelExecution, groups)
  }
  def buildIndex(data: Seq[AggrDOut])(implicit ec: ExecutionContext): Seq[Future[Index]] = {
    assert(data.nonEmpty)
    val dataI = data.asInstanceOf[Seq[AggrDOutImpl]].toArray
    val setup = Single(dataI.map(_.setup).distinct)
    (0 until setup.outCount).map{ outPos =>
      setup.parallelExecution.execute{ partPos =>
        val bufferPos = setup.bufferPos(outPos, partPos)
        val src: Array[RIndexPair] = dataI.flatMap(_.byOutThenTarget(bufferPos))
        rIndexUtil.build(memoryOptimizing.indexPower, src, rIndexValueOperations)
      }.map(rIndexUtil.merge(_,rIndexValueOperations))
    }
  }
  def countResults(data: Seq[AggrDOut]): ProfilingCounts =
    data.asInstanceOf[Seq[AggrDOutImpl]]
      .foldLeft(ProfilingCounts(0L,0L))((res,aggr) => res.copy(
        callCount = res.callCount + aggr.profilingCounts.callCount,
        resultCount = res.resultCount + aggr.profilingCounts.resultCount,
      ))

  def createOutFactory(pos: Int, dir: Int): OutFactory[Any, Product] =
    new OutFactoryImpl(this,pos,dir)

  @SuppressWarnings(Array("org.wartremover.warts.TryPartial")) def getInstantly(future: Future[Index]): Index = future.value.get.get

  def getValue(dOut: DOut): Product = dOut match { case d: DOutImpl => getItem(asCount(d.rIndexItem)) }
  def addNS(key: AssembledKey, ns: String): AssembledKey = key match {
    case k: JoinKeyImpl => k.copy(keyAlias=k.keyAlias+"#"+ns)
  }
}

final class ChangedMultiForPart(val items: Array[Product]) extends MultiForPart {
  def isChanged: Boolean = true
}
final class UnchangedMultiForPart(getItems: ()=>Array[Product]) extends MultiForPart {
  def isChanged: Boolean = false
  lazy val items: Array[Product] = getItems()
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
    inner.add(setup.bufferPos(v.pos,v.rIndexKey), v)
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
final class DOutImpl(val pos: Int, val rIndexKey: RIndexKey, val rIndexItem: RIndexItem) extends DOut with RIndexPair

final class OutFactoryImpl(util: IndexUtilImpl, pos: Int, dir: Int) extends OutFactory[Any, Product] {
  def result(key: Any, value: Product): DOut = {
    new DOutImpl(pos,util.oKey(key),util.asUProducts(util.makeCount(value,dir)))
  }
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
  composes: IndexUtil,
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
          patchStart = profiler.time
          diffs <- composes.zipMergeIndex(noUpdates.diffs, indexDiffs)
          results <- composes.zipMergeIndex(noUpdates.results, indexDiffs)
          patchLog = profiler.handle(join, 2L, patchStart, findChangesLog)
        } yield new IndexUpdates(diffs, results, patchLog)
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
    val transforms: List[WorldPartExpression] = expressionsByPriorityWithLoops ::: backStage ::: Nil
    //val transformAllOnce: WorldTransition=>WorldTransition = Function.chain(transforms.map(h=>h.transform(_)))
    replaceImplFactory.create(rulesByPriority, transforms)
  }
}

@c4("AssembleApp") final class DefExpressionsDumper extends ExpressionsDumper[Unit] {
  private def ignoreTheSamePath(path: Path): Unit = ()
  def dump(expressions: List[DataDependencyTo[_] with DataDependencyFrom[_]]): Unit = {
    val content = expressions.map(expression=>s"${expression.inputWorldKeys.mkString(" ")} ==> ${expression.outputWorldKeys.mkString(" ")}").mkString("\n")
    ignoreTheSamePath(Files.write(Paths.get("/tmp/c4rules.out"),content.getBytes(UTF_8)))
  }
}

@c4multi("AssembleApp") final class ReplaceImpl(
  val active: List[WorldPartRule],
  transforms: List[WorldPartExpression]
)(
  composes: IndexUtil, readModelUtil: ReadModelUtil,
) extends Replace {
  @tailrec def transformTail(transforms: List[WorldPartExpression], transition: WorldTransition): WorldTransition =
    if(transforms.isEmpty) transition
    else transformTail(transforms.tail, transforms.head.transform(transition))
  def transformAllOnce(transition: WorldTransition): WorldTransition =
    transformTail(transforms,transition)
  def transformUntilStable(left: Int, transition: WorldTransition): Future[WorldTransition] = {
    implicit val executionContext: ExecutionContext = transition.executionContext.value
    for {
      stable <- readModelUtil.isEmpty(executionContext)(transition.diff) //seq
      res <- {
        if(stable) Future.successful(transition)
        else if(left > 0) transformUntilStable(left-1, transformAllOnce(transition))
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
    val prevTransition = new WorldTransition(None,emptyReadModel,prevWorld,profiler,Future.successful(Nil),executionContext,Nil)
    val currentWorld = readModelUtil.op(Merge[AssembledKey,Future[Index]](_=>false/*composes.isEmpty*/,(a,b)=>for {
      seq <- Future.sequence(Seq(a,b))
    } yield composes.mergeIndex(seq) ))(prevWorld,diff)
    val nextTransition = new WorldTransition(Option(prevTransition),diff,currentWorld,profiler,Future.successful(Nil),executionContext,Nil)
    for {
      finalTransition <- transformUntilStable(1000, nextTransition)
      ready <- readModelUtil.changesReady(prevWorld,finalTransition.result) //seq
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

object MeasureP {

  import java.util.concurrent.ConcurrentHashMap
  import java.util.concurrent.atomic.LongAdder
  import scala.jdk.CollectionConverters._

  val state = new ConcurrentHashMap[(String, String), LongAdder]
  val maxState = new ConcurrentHashMap[String,Integer]
  //def log2(value: Int): Int = Integer.SIZE - Integer.numberOfLeadingZeros(value)
  def sz(m: Map[_, _]): String = m.size match {
    case 0 => "0"
    case 1 => "1"
    case a if a < 256 => "C"
    case _ => "M"
  }
  def inc(key: (String, String), value: Int): Unit =
    state.computeIfAbsent(key, _ => new LongAdder).add(value)

  def apply(hint: String, size: Int): Unit = {
    inc((hint, "o"), 1)
    inc((hint, "i"), size)
    //maxState.computeIfAbsent(hint,_=>0)
    //maxState.compute(hint,(k,v)=>Math.max(v,size))
    //inc((hint, sz(m0), sz(m1), sz(m2)))
    //inc((hint, "*", "*", "*"))
  }

  def out(): Unit = {
    state.keySet.asScala.toSeq.sorted.foreach(key=>println(s"ME: ${key._1} ${key._2} ${state.get(key).longValue}"))
    //maxState.keySet.asScala.toSeq.sorted.foreach(key=>println(s"ME:MAX: ${key} ${maxState.get(key)}"))
  }
}

@c4("AssembleApp") final class StartUpSpaceProfilerImpl(
  rIndexUtil: RIndexUtil,
  readModelUtil: ReadModelUtil,
  indexUtil: IndexUtil,
) extends StartUpSpaceProfiler {

  def out(readModelA: ReadModel): Unit = {
    val readModel = readModelUtil.toMap(readModelA)

    /*
    val cols = Seq("S","F","L","M1","MH","MC","MM")
    val stat = for {
      (assembledKey,index) <- readModel
    } yield {
      val data = getData(index)
      val sm = data.foldLeft(Map.empty[String,Long]){(res,kv)=>
        val (key,dMultiSet) = kv
        val text = dMultiSet match {
          case set: SingleOuterMultiSet => "S"
          case set: FewOuterMultiSet => "F"
          case set: MultiOuterMultiSet =>
            set.data match {
              case m if m.exists(_._2.size!=1) => "L"
              case data =>
                //val c = data.groupMapReduce{ case (InnerKey(primaryKey,hash),products) => primaryKey }(_=>1)(_+_).values.max //maxProdsPerPK
                val c = data.values.flatten.size // counts per ms
                //if(c<=1) "M1" else
                //if(c<=16) "MH" else
                if(c<=256) "MC" else "MM"
            }

        }
        res + (text->(res.getOrElse(text,0L)+1L))
      }
      (cols.map(c=>sm.getOrElse(c,0L)),assembledKey)
    }
    stat.foreach(println)
    cols.zipWithIndex.foreach{ case(c,i)=>println(s"$c:${stat.map(_._1(i)).sum}") }*/
    MeasureP.out()

    /*
    def items: Iterator[(Any,Product)] = for{
      (_,index) <- readModel.iterator
      data = getData(index)
      (key,outerMS) <- data
      (_,products)<- IndexUtilImpl.getInnerMultiSet(outerMS)
      count <- products
    } yield (key, count.item)

    def inc(res: Map[String,Long], key: String): Map[String,Long] =
      res.updated(key,res.getOrElse(key,0L)+1L)

    items.foldLeft(Map.empty[String,Long]){ (res,ki) =>
      val key = ki._2.getClass.getName
      inc(inc(res,key),"ALL")
    }.toSeq.map{
      case (className,count) => (count,className)
    }.sorted.foreach {
      case (count,className) => println(s"CC: $count $className")
    }
    */
    //print("SrcId-Only: " + items.count{ case (k,i) => i.productArity == 1 && i.productElement(0) == k }.toString)

//    items.foldLeft(Map.empty[Int,Long]){ (res,item) =>
//      val key = item.productArity
//      res.updated(key,res.getOrElse(key,0L)+1L)
//    }.toSeq.foreach{
//      case (arity,count) => println(s"Arity: $arity $count")
//    }


    /*
    countItems(for {
      (_, index) <- readModel.iterator
      ms <- rIndexUtil.iterator(index)
      r <- ms match {
        case item: AssembledProduct => "AP ALL"::"AP "+item.productPrefix::Nil
        case ms: SingleOuterMultiSet => "SM ALL"::"SM "+ms.item.productPrefix::Nil
        case ms: FewOuterMultiSet =>
          for {
            item <- ms.items.toSeq
            r <- "FM ALL" :: "FM " + item.productPrefix :: Nil
          } yield r
        case ms: FewAssembledOuterMultiSet =>
          "FAM ALL"::Nil
        case _ => "ETC ALL"::Nil
      }
    } yield r).sorted.foreach {
      case (count,className) => println(s"CP: $count $className")
    }*/

    // find non-interned -- not found at 1st level, max 365
//    countItems(for {
//      (_, index) <- readModel.iterator
//      data = getData(index)
//      (key, ms) <- data
//      (_,products)<- IndexUtilImpl.getInnerMultiSet(ms)
//      count <- IndexUtilImpl.toCounts(products)
//      (fVal,fPos) <- count.item.productIterator.zipWithIndex if (fVal match {
//        case v: String => v.intern() ne v
//        case _ => false
//      })
//    } yield (count.item.productPrefix,fPos)).sorted.foreach {
//      case (count,fld) => println(s"NON-INTERN: $count $fld")
//    }


    // find Assembled in fewMS hist
//    countItems(for {
//      (_, index) <- readModel.iterator
//      data = getData(index)
//      (_, ms) <- data
//      sz <- ms match {
//        case ms: FewOuterMultiSet => ms.items.length :: Nil
//        case ms: MultiOuterMultiSet => ms.data.size :: Nil
//        case _ => 1::Nil
//      }
//    } yield sz match {
//      case n if n <= 8 => n
//      case n if n <= 16 => 16
//      case n if n <= 32 => 32
//      case n if n <= 64 => 64
//      case n if n <= 128 => 128
//      case n if n <= 256 => 256
//      case n if n <= 512 => 512
//      case n if n <= 1024 => 1024
//      case n if n <= 4096 => 4096
//      case n if n <= 65536 => 65536
//      case n if n <= 1048576 => 1048576
//      case n if n <= 16777216 => 16777216
//      case n => -1
//    }).sortBy(_._2).foreach {
//      case (count,sz) => println(s"MS-SZ: $count $sz")
//    }

    // find SrcIdOnly in MultiOuterMultiSet
//    countItems(for {
//      (_, index) <- readModel.iterator
//      ms: MultiOuterMultiSet <- rIndexUtil.iterator(index)
//      (_, products) <- IndexUtilImpl.getInnerMultiSet(ms)
//      count <- IndexUtilImpl.toCounts(products)
//      item = IndexUtilImpl.getItem(count)
//      r <- {
//        if(item.isInstanceOf[PrimaryKeyOnly]) "POK ALL" :: Nil
//        else if(item.productArity == 1 && item.productElement(0).isInstanceOf[String])
//          "MAY ALL" :: "MAY "+item.productPrefix :: Nil
//        else "BIG ALL" :: Nil
//      }
//    } yield r).sorted.foreach {
//      case (count,cl) => println(s"SrcIdOnly: $count $cl")
//    }

//    for {
//      (k0, index) <- readModel.iterator
//      ms: MultiOuterMultiSet <- rIndexUtil.iterator(index) if ms.data.size > 65536
//    } println(s"Big Values: ${ms.data.size} $k0")

    def countItems[T](iterator: Iterator[T]): Seq[(Int,T)] =
      iterator.foldLeft(Map.empty[T,Int])((res,n)=>res.updated(n,res.getOrElse(n,0)+1)).toSeq.map{
        case (className,count) => (count,className)
      }
/*
    countItems(for {
      (assembledKey,index) <- readModel.iterator
      assembledKeyS = assembledKey.toString
      _ <- rIndexUtil.keyIterator(index)
      r <- "ALL" :: assembledKeyS :: Nil
    } yield r).sorted.foreach {
      case (count,it) => println(s"index-key-count $count $it")
    }

    countItems(for {
      (assembledKey,index) <- readModel.iterator
      assembledKeyS = assembledKey.toString
      key <- rIndexUtil.keyIterator(index)
      _ <-  rIndexUtil.get(index,key)
      r <- "ALL" :: assembledKeyS :: Nil
    } yield r).sorted.foreach {
      case (count,it) => println(s"index-each-count $count $it")
    }

    countItems(for {
      (assembledKey, index: RIndexImpl) <- readModel.iterator
      bucket <- index.data
    } yield 1 << (Integer.SIZE - Integer.numberOfLeadingZeros(bucket.keys.length))).sortBy(_._2).foreach {
      case (count,it) => println(s"index-bucket-size-count $it $count")
    }

    (for {
      (assembledKey,index) <- readModel.iterator
      assembledKeyS = assembledKey.toString
      key <- rIndexUtil.keyIterator(index)
      p <- indexUtil.getValues(index,key,"") if (ToPrimaryKey(p):Object) == (key:Object)
      fn <- 0 until p.productArity if p.productElement(fn).isInstanceOf[List[_]]
      fullFieldName = p.getClass.getName +"."+ p.productElementName(fn)
      size = p.productElement(fn).asInstanceOf[List[_]].size
    } yield (fullFieldName,size)).foldLeft(Map.empty[String,(Int,Int)]){ (res,r)=>
      val (fullFieldName,size) = r
      val was = res.getOrElse(fullFieldName,(0,0))
      res.updated(fullFieldName, (was._1+1,was._2+size))
    }.toSeq.sortBy{
        case (fullFieldName,(fieldCount,valueCount)) => valueCount // - fieldCount * 2
    }.foreach {
      o => println(s"long-lists-2 $o")
    }

    (for {
      (assembledKey, index) <- readModel.iterator
      assembledKeyS = assembledKey.toString
    } yield {
      val started = System.nanoTime()
      val keyCount = rIndexUtil.keyIterator(index).size
      val sum = (for {
        key <- rIndexUtil.keyIterator(index)
        value <- rIndexUtil.get(index,key)
      } yield value.hashCode()).sum
      val period = System.nanoTime() - started
      val periodPerKey = if(keyCount > 0) period/keyCount else 0
      ("index-hashing-time-0", periodPerKey, period, assembledKeyS, sum)
    }).toSeq.sorted.foreach(println)
    */
    // vals count, prod count
    // bucket count by size
    // top indexes;

  }
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


