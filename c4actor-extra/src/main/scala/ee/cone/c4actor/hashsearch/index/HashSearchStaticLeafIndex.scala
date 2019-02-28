package ee.cone.c4actor.hashsearch.index

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

import StaticHashSearchApi._
import ee.cone.c4actor.HashSearch._
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.hashsearch.base._
import ee.cone.c4actor.hashsearch.index.StaticHashSearchImpl.StaticFactoryImpl
import ee.cone.c4assemble._
import ee.cone.c4assemble.Types.{Each, Values}

object StaticHashSearchImpl {

  case class StaticNeed[Model <: Product](requestId: SrcId)

  case class StaticCount[Model <: Product](heapId: SrcId, count: Int)

  def count[Model <: Product](heapId: SrcId, lines: Values[Model]): StaticCount[Model] =
    StaticCount(heapId, lines.length)

  sealed trait Expression

  case class Leaf(ids: List[SrcId]) extends Expression

  case object FullScan extends Expression

  sealed trait Options

  case object GatherAll extends Options

  case class Optimal(priorities: Map[SrcId, Int]) extends Options

  private def heapIds(expr: Expression, options: Options) = (expr, options) match {
    case (Leaf(ids), _) ⇒ ids
    case (FullScan, _) ⇒ Nil
  }

  def heapIds[Model <: Product](indexers: Indexer[Model], cond: Condition[Model]): List[SrcId] =
    heapIds(expression(indexers)(cond), GatherAll)


  def cEstimate[Model <: Product](cond: InnerLeaf[Model], priorities: Values[StaticCount[Model]]): InnerConditionEstimate[Model] = {
    if (priorities.distinct.size != priorities.size)
      println("Warning, non singe priority", cond)
    val priorPrep = priorities.distinct
    InnerConditionEstimate(cond.srcId, Log2Pow2(priorPrep.map(_.count).sum), priorPrep.map(_.heapId).toList)
  }

  private def expression[Model <: Product](indexers: Indexer[Model]): Condition[Model] ⇒ Expression =
    c ⇒ indexers.heapIdsBy(c).map(Leaf).getOrElse(FullScan)

  class StaticFactoryImpl(
    modelConditionFactory: ModelConditionFactory[Unit],
    serializer: SerializationUtils,
    idGenUtil: IdGenUtil,
    indexUtil: IndexUtil
  ) extends StaticFactory {
    def index[Model <: Product](cl: Class[Model]): Indexer[Model] =
      EmptyIndexer[Model]()(cl, modelConditionFactory.ofWithCl[Model](cl), serializer, indexUtil)

    def request[Model <: Product](condition: Condition[Model]): Request[Model] =
      Request(idGenUtil.srcIdFromStrings(condition.toString), condition)
  }

  abstract class Indexer[Model <: Product] extends StaticIndexBuilder[Model] {
    def modelClass: Class[Model]

    def modelConditionFactory: ModelConditionFactory[Model]

    def serializer: SerializationUtils

    def add[NBy <: Product, NField](lens: ProdLens[Model, NField], by: NBy)(
      implicit ranger: Ranger[NBy, NField]
    ): StaticIndexBuilder[Model] = {
      val (valueToRanges, byToRanges) = ranger.ranges(by)
      IndexerImpl(modelConditionFactory.filterMetaList(lens), by, this)(serializer, modelClass, modelConditionFactory, lens.of, valueToRanges, byToRanges.lift)
    }

    def assemble: List[Assemble]

    def heapIdsBy(condition: Condition[Model]): Option[List[SrcId]]

    def heapIds(model: Model): List[SrcId]

    def isMy(cond: InnerLeaf[Model]): Boolean
  }

  case class EmptyIndexer[Model <: Product]()(
    val modelClass: Class[Model],
    val modelConditionFactory: ModelConditionFactory[Model],
    val serializer: SerializationUtils,
    indexUtil: IndexUtil,
    debugMode: Boolean = false
  ) extends Indexer[Model] {
    def heapIdsBy(condition: Condition[Model]): Option[List[SrcId]] = None

    def heapIds(model: Model): List[SrcId] = Nil

    def isMy(cond: InnerLeaf[Model]): Boolean = false

    def assemble: List[Assemble] = new StaticAssembleShared(modelClass, debugMode, indexUtil) :: Nil
  }

  case class IndexerImpl[By <: Product, Model <: Product, Field](
    metaList: List[MetaAttr], by: By, next: Indexer[Model]
  )(
    val serializer: SerializationUtils,
    val modelClass: Class[Model],
    val modelConditionFactory: ModelConditionFactory[Model],
    of: Model ⇒ Field,
    valueToRanges: Field ⇒ List[By],
    byToRanges: Product ⇒ Option[List[By]]
  ) extends Indexer[Model] {
    def heapIdsBy(condition: Condition[Model]): Option[List[SrcId]] = for {
      c ← Option(condition.asInstanceOf[ProdCondition[By, Model]])
      a ← {
        if (byToRanges(c.by).isEmpty)
          println("[Warning] something went wrong StaticLeaf:112", metaList, c.metaList, c.by, by)
        Some(1)
      }
      if metaList == c.metaList
      ranges ← byToRanges(c.by)
    } yield heapIds(c.metaList, ranges).distinct

    def heapIds(model: Model): List[SrcId] =
      heapIds(metaList, valueToRanges(of(model)))

    private def heapIds(metaList: List[MetaAttr], ranges: List[By]): List[SrcId] = for {
      range ← ranges
    } yield {
      //println(range,range.hashCode())
      //letters3(metaList.hashCode ^ range.hashCode)
      getHeapSrcId(metaList, range)
    }

    private def getHeapSrcId(metaList: List[MetaAttr], range: By): SrcId = {
      val metaListUUID = serializer.srcIdFromMetaAttrList(metaList)
      val rangeUUID = serializer.srcIdFromOrig(range, by.getClass.getName)
      serializer.srcIdFromSeqMany(metaListUUID, rangeUUID).toString
    }

    def fltML: List[MetaAttr] ⇒ NameMetaAttr =
      _.collectFirst { case l: NameMetaAttr ⇒ l }.get

    def isMy(cond: InnerLeaf[Model]): Boolean = {
      cond.condition match {
        case a: ProdConditionImpl[By, Model, Field] ⇒ fltML(a.metaList) == fltML(metaList) && a.by.getClass.getName == by.getClass.getName
        case _ ⇒ false
      }
    }

    def assemble: List[Assemble] = new HashSearchStaticLeafAssemble[Model](modelClass, this, serializer) :: next.assemble
  }

  private def letters3(i: Int) = Integer.toString(i & 0x3FFF | 0x4000, 32)

  def single[Something]: Values[Something] ⇒ Values[Something] =
    l ⇒ Single.option(l.distinct).toList
}

trait HashSearchStaticLeafFactoryApi {
  def staticLeafFactory: StaticFactory
}

trait HashSearchStaticLeafFactoryMix extends HashSearchStaticLeafFactoryApi with SerializationUtilsApp {
  def modelConditionFactory: ModelConditionFactory[Unit]
  def idGenUtil: IdGenUtil
  def indexUtil: IndexUtil

  def staticLeafFactory: StaticFactory = new StaticFactoryImpl(modelConditionFactory, serializer, idGenUtil, indexUtil)
}

import StaticHashSearchImpl._

@assemble class HashSearchStaticLeafAssembleBase[Model <: Product](
  modelCl: Class[Model],
  indexer: Indexer[Model],
  serializer: SerializationUtils
) extends   HashSearchAssembleSharedKeys {
  type StaticHeapId = SrcId
  type LeafCondId = SrcId

  def respLineByHeap(
    respLineId: SrcId,
    respLine: Each[Model]
  ): Values[(StaticHeapId, Model)] = for {
    tagId ← indexer.heapIds(respLine).distinct
  } yield tagId → respLine


  def reqByHeap(
    leafCondId: SrcId,
    leafCond: Each[InnerLeaf[Model]]
  ): Values[(StaticHeapId, StaticNeed[Model])] =
    if(indexer.isMy(leafCond))
      for {
        heapId ← heapIds(indexer, leafCond.condition)
      } yield {
        heapId → StaticNeed[Model](ToPrimaryKey(leafCond))
      }
    else Nil

  def neededRespHeapPriority(
    requestId: SrcId,
    request: Each[InnerLeaf[Model]],
    @by[LeafCondId] priorities: Values[StaticCount[Model]]
  ): Values[(SrcId, InnerConditionEstimate[Model])] =
    if(indexer.isMy(request))
      List(WithPK(cEstimate(request, priorities)))
    else Nil
}


@assemble class StaticAssembleSharedBase[Model <: Product](
  modelCl: Class[Model],
  debugMode: Boolean,// = false
  indexUtil: IndexUtil
) extends   HashSearchAssembleSharedKeys {
  type StaticHeapId = SrcId
  type LeafCondId = SrcId

  def respHeapPriorityByReq(
    heapId: SrcId,
    @by[StaticHeapId] respLines: Values[Model],
    @by[StaticHeapId] need: Each[StaticNeed[Model]]
  ): Values[(LeafCondId, StaticCount[Model])] =
    List(ToPrimaryKey(need) → count(heapId, respLines))

  def handleRequest(
    heapId: SrcId,
    @by[StaticHeapId] responses: Values[Model],
    @by[SharedHeapId] request: Each[InnerUnionList[Model]]
  ): Values[(SharedResponseId, ResponseModelList[Model])] = {
    //TimeColored("r", ("handleRequest", heapId, requests.size, responses.size), requests.isEmpty || !debugMode) {
    val lines = indexUtil.mayBePar(responses).filter(request.check)
    List(request.srcId → ResponseModelList(request.srcId + heapId, lines.toList))
    //}
  }
}