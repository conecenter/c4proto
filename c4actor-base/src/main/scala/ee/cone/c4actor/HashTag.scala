
package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._

trait HashTagHeapPriority extends Product {
  def srcId: SrcId
  def priority: Int
}

trait HashTagRequest extends Product {
  def expression: HashTagExpression
}

trait HashTagExpression extends Product {
  def heapIds(options: HashTagHeapIdsOptions): List[SrcId]
}

trait HashTagIndexer[RespLine<:Product] extends Product { //todo impl
  def heapIds(respLine: RespLine): List[SrcId]
}

sealed trait HashTagHeapIdsOptions
case object GatherAllHashTagHeapIdsOptions extends HashTagHeapIdsOptions
case class OptimalHashTagHeapIdsOptions(priorities: Map[SrcId,Int]) extends HashTagHeapIdsOptions
object OptimalHashTagHeapIdsOptions {
  def apply(priorities: Values[HashTagHeapPriority]): HashTagHeapIdsOptions =
    OptimalHashTagHeapIdsOptions(priorities.groupBy(_.srcId).transform((k,v)⇒Single(v).priority))
}

case class LeafHashTagExpression() extends HashTagExpression {
  def heapIds(options: HashTagHeapIdsOptions): List[SrcId] = ???
}

case class IntersectHashTagExpression(children: List[HashTagExpression]) extends HashTagExpression {
  def heapIds(options: HashTagHeapIdsOptions): List[SrcId] = {
    val groups = children.map(_.heapIds(options))
    options match {
      case GatherAllHashTagHeapIdsOptions ⇒ groups.flatten.distinct
      case OptimalHashTagHeapIdsOptions(priorities) ⇒ groups.maxBy(_.map(priorities).min)
    }
  }
}

case class UnionHashTagExpression(children: List[HashTagExpression]) extends HashTagExpression {
  def heapIds(options: HashTagHeapIdsOptions): List[SrcId] = {
    val groups = children.map(_.heapIds(options))
    groups.flatten.distinct
  }
}



//import collection.immutable.Seq

class HashTagOperations[
  RespLine<:Product,
  Request<:HashTagRequest,
  Priority<:HashTagHeapPriority,
  ReqResp<:Product
](
  val indexers: List[HashTagIndexer[RespLine]],
  val createReqResp: (SrcId,Request,List[RespLine]) ⇒ ReqResp,
  val createPriority: (SrcId,Int) ⇒ Priority
) {
  def priority(heapSrcId: SrcId, respLines: Values[RespLine]): Priority =
    createPriority(heapSrcId,java.lang.Long.numberOfLeadingZeros(respLines.length))

  def filter(request: Request, respLines: Values[RespLine]): Values[(SrcId,RespLine)] = {
    val requestId = ToPrimaryKey(request)
    val res = respLines.filter(???)
    res.map(requestId→_)
  }



}


//todo make
@assemble class HashTagAssemble[
  RespLine<:Product,
  Request<:HashTagRequest,
  Priority<:HashTagHeapPriority,
  ReqResp<:Product
](
  classOfRespLine: Class[RespLine],
  classOfRequest: Class[Request],
  classOfPriority: Class[Priority],
  classOfReqResp: Class[ReqResp],
  op: HashTagOperations[RespLine,Request,Priority,ReqResp]
) extends Assemble {
  type HashTagHeapId = SrcId
  type HashTagMoreHeapId = SrcId
  type HashTagRequestId = SrcId

  def respByHeap(
    respLineSrcId: SrcId,
    respLines: Values[RespLine]
  ): Values[(HashTagHeapId,RespLine)] = for {
    respLine ← respLines
    indexer ← op.indexers
    heapId ← indexer.heapIds(respLine)
  } yield heapId → respLine

  def reqByHeap(
    requestSrcId: SrcId,
    requests: Values[Request]
  ): Values[(HashTagHeapId,Request)] = for {
    request ← requests
    heapId ← request.expression.heapIds(GatherAllHashTagHeapIdsOptions)
  } yield heapId → request

  def respHeapPriorityByReq(
    heapSrcId: SrcId,
    @by[HashTagHeapId] respLines: Values[RespLine],
    @by[HashTagHeapId] requests: Values[Request]
  ): Values[(HashTagRequestId,Priority)] = for {
    request ← requests
  } yield ToPrimaryKey(request) → op.priority(heapSrcId,respLines)

  def neededRespHeapPriority(
    requestSrcId: SrcId,
    requests: Values[Request],
    @by[HashTagRequestId] priorities: Values[Priority]
  ): Values[(HashTagMoreHeapId,Request)] = for {
    request ← requests
    heapId ← request.expression.heapIds(OptimalHashTagHeapIdsOptions(priorities))
  } yield heapId → request

  def respByReq(
    heapSrcId: SrcId,
    @by[HashTagHeapId] respLines: Values[RespLine],
    @by[HashTagMoreHeapId] requests: Values[Request]
  ): Values[(HashTagRequestId,RespLine)] = for {
    request ← requests
    res ← op.filter(request,respLines)
  } yield res

  def responses(
    requestSrcId: SrcId,
    requests: Values[Request],
    respLines: Values[RespLine]
  ): Values[(SrcId,ReqResp)] = for {
    request ← requests
  } yield WithPK(op.createReqResp(ToPrimaryKey(request), request, respLines.toList.distinct))
  //.foldRight(List.empty[RespLine])((line,res)⇒)


}
