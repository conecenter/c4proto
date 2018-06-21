
package ee.cone.c4assemble

// see Topological sorting

import Types._
import ee.cone.c4actor.{PreHashed, PreHashing}
import ee.cone.c4assemble.IndexTypes.{DMultiSet, InnerIndex}
import ee.cone.c4assemble.Merge.Compose
import ee.cone.c4assemble.TreeAssemblerTypes.Replace

import scala.annotation.tailrec
import scala.collection.GenIterable
import scala.collection.immutable.{Iterable, Map, Seq, TreeMap}

class NonSingleValuesException(k: Product, v: Int) extends Exception

case class DValuesImpl(asMultiSet: DMultiSet) extends Seq[Product] {
  def length: Int = asMultiSet.size
  private def value(kv: (PreHashed[Product],Int)): Product = {
    val(k,count) = kv
    if(count<1) throw new NonSingleValuesException(k.value,count)
    if(count>1) println(s"non-single ($count) ${k.value.getClass}")
    k.value
  }
  def apply(idx: Int): Product = value(asMultiSet.view(idx,idx+1).head)
  def iterator: Iterator[Product] = asMultiSet.iterator.map(value)
}

class IndexImpl(val data: InnerIndex, val getMS: Any⇒Option[DMultiSet]) extends Index {
  def keySet: Set[Any] = data.keySet
  def getValues(k: Any): Values[Product] =
    getMS(k).fold(Nil:Values[Product])(v⇒DValuesImpl(v))
}

object IndexTypes {
  type DMultiSet = Map[PreHashed[Product],Int]
  type InnerIndex = DMap[Any,DMultiSet]
}

// todo All on upd, Tree on upd

object IndexUtilImpl {
  def toOrdered(inner: Compose[DMultiSet]): Compose[DMultiSet] = {
    val empty: DMultiSet = TreeMap.empty[PreHashed[Product],Int](Ordering.by(p⇒ToPrimaryKey(p.value)))
    (a, b) ⇒ {
      val res = inner(a, b)
      if(res.size > 1 && !res.isInstanceOf[TreeMap[_, _]]) empty ++ res else res
    }
  }
}

class IndexUtilImpl(
  preHashing: PreHashing,
  val nonEmptySeq: Seq[Unit] = Seq(()),
  mergeIndexInner: Compose[InnerIndex] = Merge[Any,DMultiSet](_.isEmpty,IndexUtilImpl.toOrdered(Merge(_==0,_+_)))
) extends IndexUtil {

  def mergeIndex(l: DPIterable[Index]): Index =
    makeIndex(l.collect{ case i: IndexImpl ⇒ i.data }.foldLeft(Map.empty:InnerIndex)(mergeIndexInner))

  def emptyMS: DMultiSet = Map.empty

  def makeIndex(data: InnerIndex): Index = if(data.isEmpty) emptyIndex else {
    val all = data.get(All)
    new IndexImpl(data, if(all.nonEmpty) (k:Any)⇒all else data.get)
  }

  def removingDiff(index: Index, key: Any): Index = getMS(index,key).fold(emptyIndex){
    values ⇒ makeIndex(Map(key→values.transform((k,v)⇒ -v)))
  }

  def result(key: Any, product: Product, count: Int): Index =
    makeIndex(Map(key→Map(preHashing.wrap(product)→count)))

  def getMS(index: Index, key: Any): Option[DMultiSet] = index match {
    case i if i eq EmptyIndex ⇒ None
    case i: IndexImpl ⇒ i.getMS(key)
  }

  def partition(currentIndex: Index, diffIndex: Index, key: Any): Partitioning = {
    getMS(currentIndex,key).fold(Nil:Partitioning){currentValues ⇒
      val diffValues = getMS(diffIndex,key).getOrElse(emptyMS)
      val changed =
        diffValues.keys.filter(currentValues.contains).map(_.value)
      lazy val unchanged =
        currentValues.keys.filter(k⇒ !diffValues.contains(k)).map(_.value)
      val unchangedRes = (false,()⇒unchanged) :: Nil
      if(changed.nonEmpty) (true,()⇒changed) :: unchangedRes else unchangedRes
    }
  }

  def invalidateKeySet(diffIndexSeq: Seq[Index]): Seq[Index] ⇒ Set[Any] = {
    val diffKeySet = diffIndexSeq.map(_.keySet).reduce(_++_)
    if(diffKeySet.contains(All))
      (indexSeq:Seq[Index]) ⇒ indexSeq.map(_.keySet).reduce(_++_) - All
    else (indexSeq:Seq[Index]) ⇒ diffKeySet
  }
}

////////////////////////////////////////////////////////////////////////////////

class IndexFactoryImpl(
  val util: IndexUtil,
  profiler: AssembleProfiler,
  updater: IndexUpdater
) extends IndexFactory {
  def createJoinMapIndex(join: Join):
    WorldPartExpression
      with DataDependencyFrom[Index]
      with DataDependencyTo[Index]
  = new JoinMapIndex(join, updater, util, profiler.get(join.name))
}

class JoinMapIndex(
  join: Join,
  updater: IndexUpdater,
  composes: IndexUtil,
  profiler: String ⇒ Int ⇒ Unit
) extends WorldPartExpression
  with DataDependencyFrom[Index]
  with DataDependencyTo[Index]
{
  def assembleName = join.assembleName
  def name = join.name
  def inputWorldKeys: Seq[AssembledKey] = join.inputWorldKeys
  def outputWorldKey: AssembledKey = join.outputWorldKey
  override def toString: String = s"${super.toString} ($assembleName,$name,$inputWorldKeys,$outputWorldKey)"

  def transform(transition: WorldTransition): WorldTransition = {
    //println(s"rule $outputWorldKey <- $inputWorldKeys")
    if (inputWorldKeys.forall(k ⇒ k.of(transition.diff).keySet.isEmpty)) transition
    else { //
      val end = profiler(s"calculate ${transition.isParallel}")
      val worlds = Seq(
        -1→inputWorldKeys.map(_.of(transition.prev.get.result)),
        +1→inputWorldKeys.map(_.of(transition.result))
      )
      val joinRes = join.joins(
        if(transition.isParallel) worlds.par else worlds,
        inputWorldKeys.map(_.of(transition.diff))
      )
      end(joinRes.size)
      val indexDiff = composes.mergeIndex(joinRes)
      if(indexDiff.keySet.isEmpty) transition else {
        val end = profiler("patch    ")
        val nextDiff = composes.mergeIndex(Seq(outputWorldKey.of(transition.diff), indexDiff))
        val nextResult = composes.mergeIndex(Seq(outputWorldKey.of(transition.result), indexDiff))
        end(indexDiff.keySet.size)
        updater.setPart(outputWorldKey)(nextDiff, nextResult)(transition)
      }
    }
  }
}

object NoAssembleProfiler extends AssembleProfiler {
  def get(ruleName: String): String ⇒ Int ⇒ Unit = dummy
  private def dummy(startAction: String)(finalCount: Int): Unit = ()
}

class TreeAssemblerImpl(
  composes: IndexUtil,
  byPriority: ByPriority, expressionsDumpers: List[ExpressionsDumper[Unit]],
  optimizer: AssembleSeqOptimizer, backStageFactory: BackStageFactory
) extends TreeAssembler {
  def replace: List[DataDependencyTo[_]] ⇒ Replace = rules ⇒ {
    val expressions/*: Seq[WorldPartExpression]*/ =
      rules.collect{ case e: WorldPartExpression with DataDependencyTo[_] with DataDependencyFrom[_] ⇒ e }
      //handlerLists.list(WorldPartExpressionKey)
    type ExprFrom = WorldPartExpression with DataDependencyFrom[_]
    type ExprByOutput = Map[AssembledKey, Seq[ExprFrom]]
    val originals: ExprByOutput = rules.collect{
      case e: OriginalWorldPart[_] ⇒ e.outputWorldKey → Nil
    }.toMap
    //umlClients.foreach(_(s"# rules: ${rules.size}, originals: ${originals.size}, expressions: ${expressions.size}"))
    val byOutput: ExprByOutput = expressions.groupBy(_.outputWorldKey)
    val permitWas: ExprByOutput = byOutput.keys.collect{
      case k: JoinKey if !k.was ⇒ k.copy(was=true) → Nil
    }.toMap
    val uses = originals ++ permitWas ++ byOutput
    val expressionsByPriority: List[ExprFrom] =
      byPriority.byPriority[ExprFrom,ExprFrom](
        item⇒(item.inputWorldKeys.flatMap(uses).toList, _ ⇒ item)
      )(expressions).reverse

    expressionsDumpers.foreach(_.dump(expressionsByPriority.map{
      case e: DataDependencyTo[_] with DataDependencyFrom[_] ⇒ e
    }))

    val expressionsByPriorityWithLoops =
      optimizer.optimize(expressionsByPriority.collect{ case e: ExprFrom with DataDependencyTo[_] ⇒ e})
    val backStage =
      backStageFactory.create(expressionsByPriorityWithLoops.collect{ case e: ExprFrom ⇒ e })
    val transforms = expressionsByPriorityWithLoops ::: backStage ::: Nil
    val transformAllOnce = Function.chain(transforms.map(h⇒h.transform(_)))
    @tailrec def transformUntilStable(left: Int, transition: WorldTransition): ReadModel =
      if(transition.diff.isEmpty) transition.result
      else if(left > 0) transformUntilStable(left-1, transformAllOnce(transition))
      else throw new Exception(s"unstable assemble ${transition.diff}")

    (diff,isParallel) ⇒ prevWorld ⇒ {
      val prevTransition = WorldTransition(None,emptyReadModel,prevWorld,isParallel)
      val currentWorld = Merge[AssembledKey,Index](_.keySet.isEmpty,(a,b)⇒composes.mergeIndex(Seq(a,b)))(prevWorld, diff)
      val transition = WorldTransition(Option(prevTransition),diff,currentWorld,isParallel)
      transformUntilStable(1000, transition)
    }
  }
}

object UMLExpressionsDumper extends ExpressionsDumper[String] {
  def dump(expressions: List[DataDependencyTo[_] with DataDependencyFrom[_]]): String = {
    val keyAliases: List[(AssembledKey, String)] =
      expressions.flatMap[AssembledKey,List[AssembledKey]](e ⇒ e.outputWorldKey :: e.inputWorldKeys.toList)
        .distinct.zipWithIndex.map{ case (k,i) ⇒ (k,s"wk$i")}
    val keyToAlias: Map[AssembledKey, String] = keyAliases.toMap
    List(
      for((k:Product,a) ← keyAliases) yield
        s"(${k.productElement(0)} ${k.productElement(2).toString.split("[\\$\\.]").last}) as $a",
      for((e,eIndex) ← expressions.zipWithIndex; k ← e.inputWorldKeys)
        yield s"${keyToAlias(k)} --> $eIndex-${e.name}",
      for((e,eIndex) ← expressions.zipWithIndex)
        yield s"$eIndex-${e.name} --> ${keyToAlias(e.outputWorldKey)}"
    ).flatten.mkString("@startuml\n","\n","\n@enduml")
  }
}

object AssembleDataDependencies {
  def apply(indexFactory: IndexFactory, assembles: List[Assemble]): List[DataDependencyTo[_]] =
    assembles.flatMap(assemble⇒assemble.dataDependencies(indexFactory))
}

object Merge {
  type Compose[V] = (V,V)⇒V
  def bigFirst[K,V](inner: Compose[DMap[K,V]]): Compose[DMap[K,V]] =
    (a,b) ⇒ if(a.size < b.size) inner(b,a) else inner(a,b)
  def apply[K,V](isEmpty: V⇒Boolean, inner: Compose[V]): Compose[DMap[K,V]] =
    bigFirst((big,small) ⇒ small.foldLeft(big){ (res,kv)⇒
      val (k,was) = kv
      val will = res.get(k).fold(was)(inner(_,was))
      if(isEmpty(will)) res - k else res + (k → will)
    })
}

// if we need more: scala rrb vector, java...binarySearch
// also consider: http://docs.scala-lang.org/overviews/collections/performance-characteristics.html





//type DPMap[K,V] = GenMap[K,V] //ParMap[K,V]
//
/*



  val wrapIndex: Object ⇒ Any ⇒ Option[DMultiSet] = in ⇒ {
    val index = in.asInstanceOf[Index]
    val all = index.get(All)
    if(all.nonEmpty) (k:Any)⇒all else (k:Any)⇒index.get(k)
  },
  val wrapValues: Option[DMultiSet] ⇒ Values[Product] =
    _.fold(Nil:Values[Product])(m⇒DValuesImpl(m.asInstanceOf[TreeMap[PreHashed[Product],Int]])),
  val mergeIndex: Compose[Index] = Merge[Any,DMultiSet](_.isEmpty,Merge(_==0,_+_)),
  val diffFromJoinRes: DPIterable[JoinRes]⇒Option[Index] = {
    def valuesDiffFromJoinRes(in: DPIterable[JoinRes]): Option[DMultiSet] = {
      val m = in.foldLeft(emptyMultiSet) { (res, jRes) ⇒
        val k = jRes.productHashed
        val was = res.getOrElse(k,0)
        val will = was + jRes.count
        if(will==0) res - k else res + (k→will)
      }
      if(m.isEmpty) None else Option(m.seq)
    }
    in ⇒
      val m = for {(k,part) ← in.groupBy(_.byKey); v ← valuesDiffFromJoinRes(part) } yield k→v
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
  def group[K,V](by: JoinRes⇒K, wrap: DPMap[K,V]⇒DMap[K,V], inner: DPIterable[JoinRes] ⇒ Option[V]): DPIterable[JoinRes] ⇒ Option[DMap[K,V]] =
    (in:DPIterable[JoinRes]) ⇒ {
      val m = for {(k,part) ← in.groupBy(by); v ← inner(part) } yield k→v
      if(m.isEmpty) None else Option(wrap(m))
    }
  def sumOpt: DPIterable[JoinRes] ⇒ Option[Int] = part ⇒ {
    val sum = part.map(_.count).sum
    if(sum==0) None else Option(sum)
  }
}
val diffFromJoinRes: DPIterable[JoinRes]⇒Option[Index] =
    IndexFactoryUtil.group[Any,DMultiSet](_.byKey, _.seq.toMap,
      IndexFactoryUtil.group[PreHashed[Product],Int](_.productHashed, emptyMultiSet++_, IndexFactoryUtil.sumOpt)
    )

*/

/*
object IndexFactoryUtil {
  def group[K,V](by: JoinRes⇒K, empty: V, isEmpty: V⇒Boolean, inner: (V,JoinRes)⇒V): (DMap[K,V],JoinRes)⇒DMap[K,V] =
    (res,jRes) ⇒ {
      val k = by(jRes)
      val was = res.getOrElse(k,empty)
      val will = inner(was,jRes)
      if(isEmpty(will)) res - k else res + (k→will)
    }
}
val diffFromJoinRes: DPIterable[JoinRes]⇒Option[Index] =
    ((in:DPIterable[JoinRes])⇒in.foldLeft(emptyIndex)(
      IndexFactoryUtil.group[Any,DMultiSet](_.byKey, emptyMultiSet, _.isEmpty,
        IndexFactoryUtil.group[PreHashed[Product],Int](_.productHashed, 0, _==0,
          (res,jRes)⇒res+jRes.count
        )
      )
    )).andThen(in⇒Option(in).filter(_.nonEmpty))
*/


