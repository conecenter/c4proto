package ee.cone.c4actor

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

import ee.cone.c4actor.HashSearch.{Factory, IndexBuilder, Request, Response}
import ee.cone.c4actor.HashSearchImpl._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble._
import ee.cone.c4assemble.Types.{Each, Values}

object HashSearchImpl {
  case class Need[Model<:Product](requestId: SrcId)
  case class Priority[Model<:Product](heapId: SrcId, priority: Int)
  def priority[Model<:Product](heapSrcId: SrcId, respLines: Values[Model]): Priority[Model] =
    Priority(heapSrcId,java.lang.Long.numberOfLeadingZeros(respLines.length))
  sealed trait Expression
  trait Branch extends Expression {
    def left: Expression
    def right: Expression
  }
  case class Leaf(ids: List[SrcId]) extends Expression
  case class Intersect(left: Expression, right: Expression) extends Branch
  case class Union(left: Expression, right: Expression) extends Branch
  case object FullScan extends Expression
  sealed trait Options
  case object GatherAll extends Options
  case class Optimal(priorities: Map[SrcId,Int]) extends Options
  private def groups(b: Branch, options: Options): List[List[SrcId]] =
    List(heapIds(b.left,options),heapIds(b.right,options))
  private def heapIds(expr: Expression, options: Options) = (expr,options) match {
    case (Leaf(ids),_) ⇒ ids
    case (b: Intersect,Optimal(priorities)) ⇒
      groups(b,options).maxBy(_.map(priorities).min)
    case (b: Branch,o) ⇒ groups(b,o).flatten.distinct
    case (FullScan,_) ⇒ throw new Exception("full scan not supported")
  }
  def heapIds[Model<:Product](indexers: Indexer[Model], req: Request[Model]): List[SrcId] =
    heapIds(expression(indexers)(req.condition),GatherAll)
  def heapIds[Model<:Product](indexers: Indexer[Model], req: Request[Model], priorities: Values[Priority[Model]]): List[SrcId] =
    heapIds(expression(indexers)(req.condition),Optimal(priorities.groupBy(_.heapId).transform((k,v)⇒Single(v).priority)))

  private def expression[Model<:Product](indexers: Indexer[Model]): Condition[Model]⇒Expression = {
    def traverse: Condition[Model]⇒Expression = {
      case IntersectCondition(left,right) ⇒
        Intersect(traverse(left),traverse(right)) match {
          case Intersect(FullScan,r) ⇒ r
          case Intersect(l,FullScan) ⇒ l
          case r ⇒ r
        }
      case UnionCondition(left,right) ⇒
        Union(traverse(left),traverse(right)) match {
          case Union(FullScan,r) ⇒ FullScan
          case Union(l,FullScan) ⇒ FullScan
          case r ⇒ r
        }
      case AnyCondition() ⇒
        FullScan
      case c ⇒ indexers.heapIdsBy(c).map(Leaf).getOrElse(FullScan)
    }
    traverse
  }

  class FactoryImpl(
    modelConditionFactory: ModelConditionFactory[Unit],
    preHashing: PreHashing,
    idGenUtil: IdGenUtil
  ) extends Factory {
    def index[Model<:Product](cl: Class[Model]): Indexer[Model] =
      EmptyIndexer[Model]()(cl,modelConditionFactory.ofWithCl[Model](cl), preHashing)
    def request[Model<:Product](condition: Condition[Model]): Request[Model] =
      Request(idGenUtil.srcIdFromStrings(condition.toString),condition)
  }

  abstract class Indexer[Model<:Product] extends IndexBuilder[Model] {
    def preHashing: PreHashing
    def modelClass: Class[Model]
    def modelConditionFactory: ModelConditionFactory[Model]
    def add[NBy<:Product,NField](lens: ProdLens[Model,NField], by: NBy)(
      implicit ranger: Ranger[NBy,NField]
    ): IndexBuilder[Model] = {
      val(valueToRanges,byToRanges) = ranger.ranges(by)
      IndexerImpl(modelConditionFactory.filterMetaList(lens),by,this)(preHashing,modelClass,modelConditionFactory,lens.of,valueToRanges,byToRanges.lift)
    }
    def assemble = new HashSearchAssemble(modelClass,this, preHashing)
    def heapIdsBy(condition: Condition[Model]): Option[List[SrcId]]
    def heapIds(model: Model): List[SrcId]
  }

  case class EmptyIndexer[Model<:Product]()(
    val modelClass: Class[Model],
    val modelConditionFactory: ModelConditionFactory[Model],
    val preHashing: PreHashing
  ) extends Indexer[Model] {
    def heapIdsBy(condition: Condition[Model]): Option[List[SrcId]] = None
    def heapIds(model: Model): List[SrcId] = Nil
  }

  case class IndexerImpl[By<:Product,Model<:Product,Field](
    metaList: List[AbstractMetaAttr], by: By, next: Indexer[Model]
  )(
    val preHashing: PreHashing,
    val modelClass: Class[Model],
    val modelConditionFactory: ModelConditionFactory[Model],
    of: Model⇒Field,
    valueToRanges: Field ⇒ List[By],
    byToRanges: Product ⇒ Option[List[By]]
  ) extends Indexer[Model] {
    def heapIdsBy(condition: Condition[Model]): Option[List[SrcId]] = (
      for {
        c ← Option(condition.asInstanceOf[ProdCondition[By,Model]])
        if metaList == c.metaList
        ranges ← byToRanges(c.by)
      } yield heapIds(c.metaList, ranges).distinct
      ).orElse(next.heapIdsBy(condition))
    def heapIds(model: Model): List[SrcId] =
      heapIds(metaList, valueToRanges(of(model))) ::: next.heapIds(model)
    private def heapIds(metaList: List[AbstractMetaAttr], ranges: List[By]): List[SrcId] = for {
      range ← ranges
    } yield {
      //println(range,range.hashCode())
      letters3(metaList.hashCode ^ range.hashCode)
    }
  }
  private def letters3(i: Int) = Integer.toString(i & 0x3FFF | 0x4000, 32)
  //def single[RespLine<:Product]: Values[Request[RespLine]]⇒Values[Request[RespLine]] =
  //  l ⇒ Single.option(l.distinct).toList
}

@assemble class HashSearchAssembleBase[RespLine<:Product](
  classOfRespLine: Class[RespLine],
  indexers: Indexer[RespLine],
  preHashing: PreHashing
)   {
  type HeapId = SrcId
  type ResponseId = SrcId

  def respLineByHeap(
    respLineId: SrcId,
    respLine: Each[RespLine]
  ): Values[(HeapId,RespLine)] = for {
    tagId ← indexers.heapIds(respLine).distinct
  } yield tagId → respLine

  def reqByHeap(
    requestId: SrcId,
    request: Each[Request[RespLine]]
  ): Values[(HeapId,Need[RespLine])] = for {
    heapId ← heapIds(indexers, request)
  } yield heapId → Need[RespLine](ToPrimaryKey(request))

  def respHeapPriorityByReq(
    heapId: SrcId,
    @by[HeapId] respLines: Values[RespLine],
    @by[HeapId] need: Each[Need[RespLine]]
  ): Values[(ResponseId,Priority[RespLine])] =
    List(ToPrimaryKey(need) → priority(heapId,respLines))

  def neededRespHeapPriority(
    requestId: SrcId,
    request: Each[Request[RespLine]],
    @by[ResponseId] priorities: Values[Priority[RespLine]]
  ): Values[(HeapId,Request[RespLine])] = for {
    heapId ← heapIds(indexers, request, priorities)
  } yield heapId → request

  def respByReq(
    heapId: SrcId,
    @by[HeapId] line: Each[RespLine],
    @by[HeapId] request: Each[Request[RespLine]]
  ): Values[(ResponseId,RespLine)] =
    if(request.condition.check(line)) List(ToPrimaryKey(request) → line) else Nil

  def responses(
    requestId: SrcId,
    request: Each[Request[RespLine]],
    @by[ResponseId] @distinct respLines: Values[RespLine]
  ): Values[(SrcId,Response[RespLine])] = {
    val pk = ToPrimaryKey(request)
    List(WithPK(Response(pk, request, preHashing.wrap(Option(respLines.toList)))))
  }
  //.foldRight(List.empty[RespLine])((line,res)⇒)
}
