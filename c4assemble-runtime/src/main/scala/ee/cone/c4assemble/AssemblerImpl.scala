
package ee.cone.c4assemble

// see Topological sorting

import Types._
import ee.cone.c4assemble.IndexTypes.{DMultiSet, InnerIndex, InnerKey, Products}
import ee.cone.c4assemble.Merge.Compose
import ee.cone.c4assemble.TreeAssemblerTypes.Replace

import scala.annotation.tailrec
import scala.collection.GenIterable
import scala.collection.immutable.{Iterable, Map, Seq, TreeMap}
import scala.collection.parallel.immutable.ParVector

case class Count(item: Product, count: Int)

case class DValuesImpl(asMultiSet: DMultiSet, warning: String) extends Values[Product] {
  def length: Int = asMultiSet.size
  private def value(kv: (InnerKey,Products)): Product =
    IndexUtilImpl.single(kv._2,warning)
  def apply(idx: Int): Product = value(asMultiSet.view(idx,idx+1).head)
  def iterator: Iterator[Product] = asMultiSet.iterator.map(value)
}

class IndexImpl(val data: InnerIndex, val getMS: Any⇒Option[DMultiSet]/*, val opt: IndexOpt*/) extends Index {
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
      def text = s"non-single $warning"
      if(distinct.tail.nonEmpty) throw new Exception(text)
      if(warning.nonEmpty) println(text)
      distinct.head.item
    }

  def toOrdered(inner: Compose[DMultiSet]): Compose[DMultiSet] = { // Ordering.by can drop keys!: https://github.com/scala/bug/issues/8674
    (a, b) ⇒ {
      val res = inner(a, b)
      val tRes = if(res.size > 1 && !res.isInstanceOf[TreeMap[_, _]])
        TreeMap.empty[InnerKey,Products] ++ res else res
      tRes
    }
  }

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
}

case class JoinKeyImpl(
  was: Boolean, keyAlias: String, keyClassName: String, valueClassName: String
) extends JoinKey {
  override def toString: String =
    s"JK(${if (was) "@was " else ""}@by[$keyAlias] $valueClassName)"
  def withWas(was: Boolean): JoinKey = copy(was=was)
}

case class IndexUtilImpl()(
  val nonEmptySeq: Seq[Unit] = Seq(()),
  mergeIndexInner: Compose[InnerIndex] =
    Merge[Any,DMultiSet](v⇒v.isEmpty,
      IndexUtilImpl.toOrdered(
        Merge[InnerKey,Products](v⇒v.isEmpty, IndexUtilImpl.mergeProducts)
      )
    )
) extends IndexUtil {

  def joinKey(was: Boolean, keyAlias: String, keyClassName: String, valueClassName: String): JoinKey =
    JoinKeyImpl(was,keyAlias,keyClassName,valueClassName)

  def makeIndex(data: InnerIndex/*, opt: IndexOpt*/): Index =
    if(data.isEmpty) emptyIndex else {
      val all = data.get(All)
      new IndexImpl(data, if(all.nonEmpty) (k:Any)⇒all else data.get/*, opt*/)
    }

  def data(index: Index): InnerIndex = index match {
    case i: IndexImpl ⇒ i.data
    case i if i == emptyIndex ⇒ Map.empty
  }

  def getMS(index: Index, key: Any): Option[DMultiSet] = index match {
    case i: IndexImpl ⇒ i.getMS(key)
    case _ if index == emptyIndex ⇒ None
  }

  def isEmpty(index: Index): Boolean = data(index).isEmpty

  def keySet(index: Index): Set[Any] = data(index).keySet

  def nonEmpty(index: Index, key: Any): Boolean = {
    val res = getMS(index,key).nonEmpty
    res
  }

  def getValues(index: Index, key: Any, warning: String): Values[Product] = {
    val res = getMS(index,key).fold(Nil:Values[Product])(v ⇒ DValuesImpl(v,warning))
    res
  }


  def mergeIndex(l: DPIterable[Index]): Index = {
    val res =
      makeIndex(l.map(data).foldLeft(Map.empty: InnerIndex)(mergeIndexInner))
    res
  }

  def emptyMS: DMultiSet = Map.empty

  def removingDiff(index: Index, key: Any): Index = getMS(index,key).fold(emptyIndex){
    values ⇒ makeIndex(Map(key→values.transform((k,v)⇒ v.map(IndexUtilImpl.inverse)))/*, index match { case i: IndexImpl ⇒ i.opt }*/)
  }

  def result(key: Any, product: Product, count: Int): Index =
    makeIndex(Map(key→Map((ToPrimaryKey(product),product.hashCode)→(Count(product,count)::Nil)))/*, opt*/)

  def partition(currentIndex: Index, diffIndex: Index, key: Any, warning: String): Partitioning = {
    getMS(currentIndex,key).fold(Nil:Partitioning){currentValues ⇒
      val diffValues = getMS(diffIndex,key).getOrElse(emptyMS)
      val changed = (for {
        (k,v) ← diffValues; values ← currentValues.get(k)
      } yield IndexUtilImpl.single(values,warning)).par
      lazy val unchanged = (for {
        (k,values) ← currentValues if !diffValues.contains(k)
      } yield IndexUtilImpl.single(values,warning)).par
      val unchangedRes = (false,()⇒unchanged) :: Nil
      if(changed.nonEmpty) (true,()⇒changed) :: unchangedRes else unchangedRes
    }
  }

  def invalidateKeySet(diffIndexSeq: Seq[Index]): Seq[Index] ⇒ DPIterable[Any] = {
    val diffKeySet = diffIndexSeq.map(keySet).reduce(_ ++ _)
    val res = if(diffKeySet.contains(All)){
      (indexSeq: Seq[Index]) ⇒ (indexSeq.map(keySet).reduce(_ ++ _) - All).to[ParVector]
    } else {
      val ids = diffKeySet.to[ParVector]
      (indexSeq:Seq[Index]) ⇒ ids
    }
    res
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
  = new JoinMapIndex(join, updater, util,
    profiler.getOpt(join.name, join.inputWorldKeys, join.outputWorldKey) match {
      case Some(l) ⇒ l
      case None ⇒ profiler.get(join.name)
    }
    /*, DefIndexOpt(join.outputWorldKey)*/
  )
}

class JoinMapIndex(
  join: Join,
  updater: IndexUpdater,
  composes: IndexUtil,
  profiler: String ⇒ Int ⇒ Unit
  //indexOpt: IndexOpt
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
    //println(s"rule $outputWorldKey <- $inputWorldKeys")
    if (inputWorldKeys.forall(k ⇒ composes.isEmpty(k.of(transition.diff)))) transition
    else { //
      val end  = profiler(s"calculate    ${transition.isParallel}")
      val worlds = Seq(
        -1→inputWorldKeys.map(_.of(transition.prev.get.result)),
        +1→inputWorldKeys.map(_.of(transition.result))
      )
      val worldDiffs = inputWorldKeys.map(_.of(transition.diff))
      val joinRes = join.joins(if(transition.isParallel) worlds.par else worlds, worldDiffs)
      end(joinRes.size)
      val endR = profiler(s"find-changes ${transition.isParallel}")
      val indexDiff = composes.mergeIndex(joinRes)
      endR(composes.keySet(indexDiff).size)
      if(composes.isEmpty(indexDiff)) transition else {
        val end = profiler("patch")
        val nextDiff = composes.mergeIndex(Seq(outputWorldKey.of(transition.diff), indexDiff))
        val nextResult = composes.mergeIndex(Seq(outputWorldKey.of(transition.result), indexDiff))
        end(-1)
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
      case k: JoinKey if !k.was ⇒ k.withWas(was=true) → Nil
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
      val currentWorld = Merge[AssembledKey,Index](composes.isEmpty,(a,b)⇒composes.mergeIndex(Seq(a,b)))(prevWorld, diff)
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
    (a,b) ⇒ {
      if(a.size < b.size) inner(b,a) else inner(a,b)
    }
  def apply[K,V](isEmpty: V⇒Boolean, inner: Compose[V]): Compose[DMap[K,V]] =
    bigFirst((bigMap,smallMap) ⇒ {
      val res =
      smallMap.foldLeft(bigMap){ (resMap,kv)⇒
        val (k,smallVal) = kv
        val bigValOpt = resMap.get(k)
        val resVal = if(bigValOpt.isEmpty) smallVal else inner(bigValOpt.get,smallVal)
        if(isEmpty(resVal)) resMap - k else resMap + (k → resVal)
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
  def setOnDistinct(onDistinct: Count⇒Unit): Seq[Product]
}

case class SingleValues(item: Product) extends DValues with Seq[Product] {
  def setOnDistinct(onDistinct: Count⇒Unit): Seq[Product] = this

  def length: Int = 1
  def apply(idx: Int): Product =
    if(idx==0) item else throw new IndexOutOfBoundsException
  def iterator: Iterator[Product] = Iterator(item)
}*/

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


