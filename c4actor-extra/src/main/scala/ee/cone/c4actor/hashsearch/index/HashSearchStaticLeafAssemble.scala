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
import ee.cone.c4assemble.Types.Values

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
    serializer: SerializationUtils
  ) extends StaticFactory {
    def index[Model <: Product](cl: Class[Model]): Indexer[Model] =
      EmptyIndexer[Model]()(cl, modelConditionFactory.of[Model], serializer)

    def request[Model <: Product](condition: Condition[Model]): Request[Model] =
      Request(UUID.nameUUIDFromBytes(condition.toString.getBytes(UTF_8)).toString, condition)
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
    debugMode: Boolean = false
  ) extends Indexer[Model] {
    def heapIdsBy(condition: Condition[Model]): Option[List[SrcId]] = None

    def heapIds(model: Model): List[SrcId] = Nil

    def isMy(cond: InnerLeaf[Model]): Boolean = false

    def assemble: List[Assemble] = new StaticAssembleShared(modelClass, debugMode) :: Nil
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
      val metaListUUID = serializer.uuidFromMetaAttrList(metaList)
      val rangeUUID = serializer.uuidFromOrig(range, by.getClass.getName)
      val srcId = serializer.uuidFromSeqMany(metaListUUID, rangeUUID).toString
      s"$metaList$range$srcId"
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

  def staticLeafFactory: StaticFactory = new StaticFactoryImpl(modelConditionFactory, serializer)
}

import StaticHashSearchImpl._

@assemble class HashSearchStaticLeafAssemble[Model <: Product](
  modelCl: Class[Model],
  indexer: Indexer[Model],
  serializer: SerializationUtils
) extends Assemble with HashSearchAssembleSharedKeys {
  type StaticHeapId = SrcId
  type LeafCondId = SrcId

  def respLineByHeap(
    respLineId: SrcId,
    respLines: Values[Model]
  ): Values[(StaticHeapId, Model)] = for {
    respLine ← respLines
    tagId ← indexer.heapIds(respLine).distinct
  } yield tagId → respLine


  def reqByHeap(
    leafCondId: SrcId,
    leafConds: Values[InnerLeaf[Model]]
  ): Values[(StaticHeapId, StaticNeed[Model])] = for {
    leafCond ← leafConds
    if indexer.isMy(leafCond)
    heapId ← heapIds(indexer, leafCond.condition)
  } yield {
    heapId → StaticNeed[Model](ToPrimaryKey(leafCond))
  }

  def neededRespHeapPriority(
    requestId: SrcId,
    requests: Values[InnerLeaf[Model]],
    @by[LeafCondId] priorities: Values[StaticCount[Model]]
  ): Values[(SrcId, InnerConditionEstimate[Model])] = for {
    request ← single(requests)
    if indexer.isMy(request)
  } yield {
    WithPK(cEstimate(request, priorities))
  }
}


@assemble class StaticAssembleShared[Model <: Product](
  modelCl: Class[Model],
  debugMode: Boolean = false
) extends Assemble with HashSearchAssembleSharedKeys {
  type StaticHeapId = SrcId
  type LeafCondId = SrcId

  def respHeapPriorityByReq(
    heapId: SrcId,
    @by[StaticHeapId] respLines: Values[Model],
    @by[StaticHeapId] needs: Values[StaticNeed[Model]]
  ): Values[(LeafCondId, StaticCount[Model])] = for {
    need ← needs
  } yield ToPrimaryKey(need) → count(heapId, respLines)

  def handleRequest(
    heapId: SrcId,
    @by[StaticHeapId] responses: Values[Model],
    @by[SharedHeapId] requests: Values[InnerUnionList[Model]]
  ): Values[(SharedResponseId, ResponseModelList[Model])] = {
    TimeColored("r", ("handleRequest", heapId, requests.size, responses.size), requests.isEmpty || !debugMode) {
      val result = for {
        request ← requests.par
      } yield {
        val lines = for {
          line ← responses.par
          if request.check(line)
        } yield line
        request.srcId → ResponseModelList(request.srcId + heapId, lines.toList)
      }
      val newResult = result.to[Values]
      newResult
    }
  }
}