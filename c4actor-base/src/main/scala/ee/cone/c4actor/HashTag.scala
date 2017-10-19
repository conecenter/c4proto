
package ee.cone.c4actor


import java.util.UUID

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._

/////////////// Condition api

trait Condition[Model] extends Product {
  def check(model: Model): Boolean
}

trait ModelConditionFactory[Model] {
  def of[OtherModel<:Product]: ModelConditionFactory[OtherModel]
  def intersect: (Condition[Model],Condition[Model]) ⇒ Condition[Model]
  def union: (Condition[Model],Condition[Model]) ⇒ Condition[Model]
  def any: Condition[Model]
  def leaf[By<:Product,Field](lens: ProdLens[Model,Field], by: By)(
    implicit check: ConditionCheck[By,Field]
  ): Condition[Model]
  def filterMetaList[Field]: ProdLens[Model,Field] ⇒ List[MetaAttr]
}
trait ConditionCheck[By<:Product,Field] extends Product {
  def check: By ⇒ Field ⇒ Boolean
}
trait ProdCondition[By<:Product,Model] extends Condition[Model] {
  def by: By
  def metaList: List[MetaAttr]
}

/////////////// Ranger api

trait Ranger[By<:Product,Field] extends Product {
  def ranges: By ⇒ (Field ⇒ List[By], PartialFunction[Product,List[By]])
}

//////////  HashTagSearch api

object HashSearch {
  case class Request[Model<:Product](requestId: SrcId, condition: Condition[Model])
  case class Response[Model<:Product](srcId: SrcId, request: Request[Model], lines: List[Model])
  trait Factory {
    def index[Model<:Product](model: Class[Model]): IndexBuilder[Model]
    def request[Model<:Product](condition: Condition[Model]): Request[Model]
  }
  trait IndexBuilder[Model<:Product] {
    def add[By<:Product,Field](lens: ProdLens[Model,Field], by: By)(
      implicit ranger: Ranger[By,Field]
    ): IndexBuilder[Model]
    def assemble: Assemble
  }
}

/////////////// active

case class StrEq(value: String) //todo proto
case object StrEqCheck extends ConditionCheck[StrEq,String] {
  def check: StrEq ⇒ String ⇒ Boolean = by ⇒ value ⇒ value == by.value
}
case object StrEqRanger extends Ranger[StrEq,String] {
  def ranges: StrEq ⇒ (String ⇒ List[StrEq], PartialFunction[Product,List[StrEq]]) = {
    case StrEq("") ⇒ (
      value ⇒ List(StrEq(value)),
      { case p@StrEq(v) ⇒ List(p) }
    )
  }
}
object DefaultConditionChecks {
  implicit lazy val strEq: ConditionCheck[StrEq,String] = StrEqCheck
}
object DefaultRangers {
  implicit lazy val strEq: Ranger[StrEq,String] = StrEqRanger
}

//todo reg
trait MyApp {
  def modelConditionFactory[Unit]: ModelConditionFactory[Unit]
  lazy val hashSearchFactory: HashSearch.Factory =
    new HashSearchImpl.FactoryImpl(modelConditionFactory)
}
import DefaultConditionChecks._
import DefaultRangers._

/////////////// Condition impl

class ModelConditionFactoryImpl[Model] extends ModelConditionFactory[Model] {
  def of[OtherModel]: ModelConditionFactory[OtherModel] =
    new ModelConditionFactoryImpl[OtherModel]
  def intersect: (Condition[Model],Condition[Model]) ⇒ Condition[Model] =
    IntersectCondition(_,_)
  def union: (Condition[Model],Condition[Model]) ⇒ Condition[Model] =
    UnionCondition(_,_)
  def any: Condition[Model] =
    AnyCondition()
  def leaf[By<:Product,Field](lens: ProdLens[Model,Field], by: By)(
    implicit check: ConditionCheck[By,Field]
  ): Condition[Model] =
    ProdConditionImpl(filterMetaList(lens), by)(check.check(by),lens.of)
  def filterMetaList[Field]: ProdLens[Model,Field] ⇒ List[MetaAttr] =
    _.metaList.collect{ case l: NameMetaAttr ⇒ l }
}

case class ProdConditionImpl[By<:Product,Model,Field](
  metaList: List[MetaAttr], by: By
)(
  fieldCheck: Field⇒Boolean, of: Model⇒Field
) extends ProdCondition[By,Model] {
  def check(model: Model): Boolean = fieldCheck(of(model))
}

case class IntersectCondition[Model](
  left: Condition[Model],
  right: Condition[Model]
) extends Condition[Model] {
  def check(line: Model): Boolean = left.check(line) && right.check(line)
}
case class UnionCondition[Model](
  left: Condition[Model],
  right: Condition[Model]
) extends Condition[Model] {
  def check(line: Model): Boolean = left.check(line) || right.check(line)
}
case class AnyCondition[Model]() extends Condition[Model] {
  def check(line: Model): Boolean = true
}

/////////////// HashSearch impl

import java.nio.charset.StandardCharsets.UTF_8
import HashSearch.{Request,Response,Factory,IndexBuilder}

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
    } yield Integer.toString((metaList.hashCode ^ range.hashCode) & 0x3FF | 0x400,32) // 1[0-9a-v][0-9a-v]
  }
}


import HashSearchImpl.{Priority,Need,heapIds,priority,Indexer}

@assemble class HashSearchAssemble[RespLine<:Product](
  classOfRespLine: Class[RespLine],
  indexers: Indexer[RespLine]
) extends Assemble {
  type HeapId = SrcId

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
    request ← requests
    heapId ← heapIds(indexers, request)
  } yield heapId → Need[RespLine](ToPrimaryKey(request))

  def respHeapPriorityByReq(
    heapId: SrcId,
    @by[HeapId] respLines: Values[RespLine],
    @by[HeapId] needs: Values[Need[RespLine]]
  ): Values[(SrcId,Priority[RespLine])] = for {
    need ← needs
  } yield ToPrimaryKey(need) → priority(heapId,respLines)

  def neededRespHeapPriority(
    requestId: SrcId,
    requests: Values[Request[RespLine]],
    priorities: Values[Priority[RespLine]]
  ): Values[(HeapId,Request[RespLine])] = for {
    request ← requests
    heapId ← heapIds(indexers, request, priorities)
  } yield heapId → request

  def respByReq(
    heapId: SrcId,
    @by[HeapId] respLines: Values[RespLine],
    @by[HeapId] requests: Values[Request[RespLine]]
  ): Values[(SrcId,RespLine)] = for {
    request ← requests
    line ← respLines if request.condition.check(line)
  } yield ToPrimaryKey(request) → line

  def responses(
    requestId: SrcId,
    requests: Values[Request[RespLine]],
    respLines: Values[RespLine]
  ): Values[(SrcId,Response[RespLine])] = for {
    request ← requests
  } yield {
    val pk = ToPrimaryKey(request)
    pk → Response(pk, request, respLines.toList.distinct)
  }
  //.foldRight(List.empty[RespLine])((line,res)⇒)
}
