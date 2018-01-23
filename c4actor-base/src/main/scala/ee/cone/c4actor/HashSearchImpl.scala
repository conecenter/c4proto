package ee.cone.c4actor

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

import ee.cone.c4actor.HashSearch.{Factory, IndexBuilder, Request, Response}
import ee.cone.c4actor.HashSearchImpl._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble._
import ee.cone.c4assemble.Types.Values

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
    case (b: Union,Optimal(priorities)) ⇒
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
      case c ⇒ indexers.heapIdsBy(c).map(Leaf).getOrElse(FullScan)
    }
    traverse
  }

  class FactoryImpl(
    modelConditionFactory: ModelConditionFactory[Unit]
  ) extends Factory {
    def index[Model<:Product](cl: Class[Model]): Indexer[Model] =
      EmptyIndexer[Model]()(cl,modelConditionFactory.of[Model])
    def request[Model<:Product](condition: Condition[Model]): Request[Model] =
      Request(UUID.nameUUIDFromBytes(condition.toString.getBytes(UTF_8)).toString,condition)
  }

  abstract class Indexer[Model<:Product] extends IndexBuilder[Model] {
    def modelClass: Class[Model]
    def modelConditionFactory: ModelConditionFactory[Model]
    def add[NBy<:Product,NField](lens: ProdLens[Model,NField], by: NBy)(
      implicit ranger: Ranger[NBy,NField]
    ): IndexBuilder[Model] = {
      val(valueToRanges,byToRanges) = ranger.ranges(by)
      IndexerImpl(modelConditionFactory.filterMetaList(lens),by,this)(modelClass,modelConditionFactory,lens.of,valueToRanges,byToRanges.lift)
    }
    def assemble = new HashSearchAssemble(modelClass,this)
    def heapIdsBy(condition: Condition[Model]): Option[List[SrcId]]
    def heapIds(model: Model): List[SrcId]
  }

  case class EmptyIndexer[Model<:Product]()(
    val modelClass: Class[Model],
    val modelConditionFactory: ModelConditionFactory[Model]
  ) extends Indexer[Model] {
    def heapIdsBy(condition: Condition[Model]): Option[List[SrcId]] = None
    def heapIds(model: Model): List[SrcId] = Nil
  }

  case class IndexerImpl[By<:Product,Model<:Product,Field](
    metaList: List[MetaAttr], by: By, next: Indexer[Model]
  )(
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
    private def heapIds(metaList: List[MetaAttr], ranges: List[By]): List[SrcId] = for {
      range ← ranges
    } yield {
      //println(range,range.hashCode())
      letters3(metaList.hashCode ^ range.hashCode)
    }
  }
  private def letters3(i: Int) = Integer.toString(i & 0x3FFF | 0x4000, 32)
  def single[RespLine]: Values[Request[RespLine]]⇒Values[Request[RespLine]] =
    l ⇒ Single.option(l.distinct).toList
}

@assemble class HashSearchAssemble[RespLine<:Product](
  classOfRespLine: Class[RespLine],
  indexers: Indexer[RespLine]
) extends Assemble {
  type HeapId = SrcId
  type ResponseId = SrcId

  def respLineByHeap(
    respLineId: SrcId,
    respLines: Values[RespLine]
  ): Values[(HeapId,RespLine)] = for {
    respLine ← respLines
    tagId ← indexers.heapIds(respLine).distinct
  } yield tagId → respLine

  def reqByHeap(
    requestId: SrcId,
    requests: Values[Request[RespLine]]
  ): Values[(HeapId,Need[RespLine])] = for {
    request ← single(requests)
    heapId ← heapIds(indexers, request)
  } yield heapId → Need[RespLine](ToPrimaryKey(request))

  def respHeapPriorityByReq(
    heapId: SrcId,
    @by[HeapId] respLines: Values[RespLine],
    @by[HeapId] needs: Values[Need[RespLine]]
  ): Values[(ResponseId,Priority[RespLine])] = for {
    need ← needs
  } yield ToPrimaryKey(need) → priority(heapId,respLines)

  def neededRespHeapPriority(
    requestId: SrcId,
    requests: Values[Request[RespLine]],
    @by[ResponseId] priorities: Values[Priority[RespLine]]
  ): Values[(HeapId,Request[RespLine])] = for {
    request ← single(requests)
    heapId ← heapIds(indexers, request, priorities)
  } yield heapId → request

  def respByReq(
    heapId: SrcId,
    @by[HeapId] respLines: Values[RespLine],
    @by[HeapId] requests: Values[Request[RespLine]]
  ): Values[(ResponseId,RespLine)] = for {
    request ← requests
    line ← respLines if request.condition.check(line)
  } yield ToPrimaryKey(request) → line

  def responses(
    requestId: SrcId,
    requests: Values[Request[RespLine]],
    @by[ResponseId] respLines: Values[RespLine]
  ): Values[(SrcId,Response[RespLine])] = for {
    request ← single(requests)
  } yield {
    val pk = ToPrimaryKey(request)
    pk → Response(pk, request, respLines.toList.distinct)
  }
  //.foldRight(List.empty[RespLine])((line,res)⇒)
}
