
package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._

case class HashTagReqNeed(srcId: SrcId)
case class HashTagReqResp[Model](srcId: SrcId, request: HashTagRequest[Model], lines: List[Model])

//todo make
case class HashTagRequest[Model](
  heapExpression: HashTagHeapExpressions.Expression,
  condition: Condition[Model]
)
object HashTagRequest {
  //todo expr --> Indexer --> heapIds
  // --> pred-expr

  def apply[Model](condition: Condition[Model]): HashTagRequest[Model] =
    HashTagRequest(HashTagHeapExpressions(condition),condition)


}

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



object HashTagHeapExpressions {
  case class Priority(srcId: SrcId, priority: Int)
  def priority(heapSrcId: SrcId, respLines: Values[_]): Priority =
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
  def heapIds(expr: Expression): List[SrcId] = heapIds(expr,GatherAll)
  def heapIds(expr: Expression, priorities: Values[Priority]): List[SrcId] =
    heapIds(expr,Optimal(priorities.groupBy(_.srcId).transform((k,v)⇒Single(v).priority)))

  def apply[Model](condition: Condition[Model]): Expression = condition match {
    case IntersectCondition(left,right) ⇒
      Intersect(apply(left),apply(right)) match {
        case Intersect(FullScan,r) ⇒ r
        case Intersect(l,FullScan) ⇒ l
        case r ⇒ r
      }
    case UnionCondition(left,right) ⇒
      Union(apply(left),apply(right)) match {
        case Union(FullScan,r) ⇒ FullScan
        case Union(l,FullScan) ⇒ FullScan
        case r ⇒ r
      }
    case _ ⇒ Leaf(???)
  }
}

trait HashTagId {
  def srcId: SrcId
}

//todo make
@assemble class HashTagAssemble[RespLine<:Product,HeapId<:HashTagId,RequestId<:HashTagId](
  classOfHeapId: Class[HeapId],
  classOfRequestId: Class[RequestId],
  classOfRespLine: Class[RespLine],
  toHeapId: SrcId ⇒ HeapId,
  toRequestId: SrcId ⇒ RequestId,
  indexers: List[HashTagIndexer]
) extends Assemble {
  type Request = HashTagRequest[RespLine]
  type Priority = HashTagHeapExpressions.Priority
  type ReqResp = HashTagReqResp[RespLine]

  def respByHeap(
    respLineId: SrcId,
    respLines: Values[RespLine]
  ): Values[(HeapId,RespLine)] = for {
    respLine ← respLines
    indexer ← indexers
    heapId ← indexer.heapIds(respLine)
  } yield toHeapId(heapId) → respLine

  def reqByHeap(
    requestId: RequestId,
    requests: Values[Request]
  ): Values[(HeapId,HashTagReqNeed)] = for {
    request ← requests
    heapId ← HashTagHeapExpressions.heapIds(request.heapExpression)
  } yield toHeapId(heapId) → HashTagReqNeed(ToPrimaryKey(request))

  def respHeapPriorityByReq(
    heapId: HeapId,
    respLines: Values[RespLine],
    requests: Values[HashTagReqNeed]
  ): Values[(RequestId,Priority)] = for {
    request ← requests
  } yield toRequestId(ToPrimaryKey(request)) → HashTagHeapExpressions.priority(heapId.srcId,respLines)

  def neededRespHeapPriority(
    requestId: RequestId,
    requests: Values[Request],
    priorities: Values[Priority]
  ): Values[(HeapId,Request)] = for {
    request ← requests
    heapId ← HashTagHeapExpressions.heapIds(request.heapExpression, priorities)
  } yield toHeapId(heapId) → request

  def respByReq(
    heapId: HeapId,
    respLines: Values[RespLine],
    requests: Values[Request]
  ): Values[(RequestId,RespLine)] = for {
    request ← requests
    line ← respLines if request.condition.check(line)
  } yield toRequestId(ToPrimaryKey(request)) → line

  def responses(
    requestId: RequestId,
    requests: Values[Request],
    respLines: Values[RespLine]
  ): Values[(RequestId,ReqResp)] = for {
    request ← requests
  } yield {
    val pk = ToPrimaryKey(request)
    toRequestId(pk) → HashTagReqResp(pk, request, respLines.toList.distinct)
  }
  //.foldRight(List.empty[RespLine])((line,res)⇒)
}
