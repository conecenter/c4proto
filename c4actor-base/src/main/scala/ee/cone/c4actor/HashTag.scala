
package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._





trait LeafConditionFactory[By,Field] {
  def create[Model]: (ProdLens[Model,Field], By) ⇒ Condition[Model]
}


trait HashTagIndexer extends Product { //todo impl
  def heapIds(respLine: Product): List[SrcId]
}



trait Condition[Model] extends Product {
  def check(line: Model): Boolean
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



object HashTagExchange {
  case class Request[Model](requestId: SrcId, condition: Condition[Model])
  case class Response[Model](srcId: SrcId, request: Request[Model], lines: List[Model])
  def request[Model](condition: Condition[Model]): Request[Model] =
    Request(???,condition)

}

import HashTagExchange.{Request,Response}

//todo make


  //todo expr --> Indexer --> heapIds
  // --> pred-expr





object HashTagHeapExpressions {
  case class Need[Model](requestId: SrcId)


  case class Priority[Model](heapId: SrcId, priority: Int)
  def priority[Model](heapSrcId: SrcId, respLines: Values[Model]): Priority[Model] =
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
    //  full scan not supported
  }
  def heapIds[Model](req: Request[Model]): List[SrcId] =
    heapIds(expression(req.condition),GatherAll)
  def heapIds[Model](req: Request[Model], priorities: Values[Priority[Model]]): List[SrcId] =
    heapIds(expression(req.condition),Optimal(priorities.groupBy(_.heapId).transform((k,v)⇒Single(v).priority)))

  private def expression[Model](condition: Condition[Model]): Expression = condition match {
    case IntersectCondition(left,right) ⇒
      Intersect(expression(left),expression(right)) match {
        case Intersect(FullScan,r) ⇒ r
        case Intersect(l,FullScan) ⇒ l
        case r ⇒ r
      }
    case UnionCondition(left,right) ⇒
      Union(expression(left),expression(right)) match {
        case Union(FullScan,r) ⇒ FullScan
        case Union(l,FullScan) ⇒ FullScan
        case r ⇒ r
      }
    case _ ⇒ Leaf(???)
  }
}


import HashTagHeapExpressions.{Priority,Need,heapIds,priority}


//todo make
@assemble class HashTagAssemble[RespLine<:Product](
  classOfRespLine: Class[RespLine],
  indexers: List[HashTagIndexer]
) extends Assemble {
  type HeapId = SrcId

  def respByHeap(
    respLineId: SrcId,
    respLines: Values[RespLine]
  ): Values[(HeapId,RespLine)] = for {
    respLine ← respLines
    indexer ← indexers
    tagId ← indexer.heapIds(respLine)
  } yield tagId → respLine

  def reqByHeap(
    requestId: SrcId,
    requests: Values[Request[RespLine]]
  ): Values[(HeapId,Need[RespLine])] = for {
    request ← requests
    heapId ← heapIds(request)
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
    heapId ← heapIds(request, priorities)
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
