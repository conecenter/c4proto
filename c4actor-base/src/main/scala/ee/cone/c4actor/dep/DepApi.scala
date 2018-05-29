package ee.cone.c4actor.dep

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.DepTypes.{DepCtx, DepRequest}
import ee.cone.c4assemble.Types.Values

import scala.collection.immutable.Seq

/******************************************************************************/
// general api for code that uses and returns Dep-s

case class Resolvable[+A](value: Option[A], requests: Seq[DepRequest] = Nil) //?hashed

trait Dep[A] {
  def flatMap[B](f: A ⇒ Dep[B]): Dep[B]
  def map[B](f: A ⇒ B): Dep[B]
  def resolve(ctx: DepCtx): Resolvable[A]
}

object DepTypes {
  type DepRequest = Product
  type DepCtx = Map[DepRequest, _]
  type GroupId = SrcId
}

trait DepFactory extends Product {
  def parallelSeq[A](value: Seq[Dep[A]]): Dep[Seq[A]]
}

trait DepAsk[In<:Product,Out] extends Product {
  def ask: In⇒Dep[Out]
}
trait DepAskFactory extends Product {
  def forClasses[In<:Product,Out](in: Class[In], out: Class[Out]): DepAsk[In,Out]
}

trait DepHandler extends Product {
  def className: String
  def handle: DepRequest ⇒ DepCtx ⇒ Resolvable[_]
}
trait DepHandlerFactory extends Product {
  def by[In<:Product,Out](ask: DepAsk[In,Out])(handler: In ⇒ DepCtx ⇒ Resolvable[Out]): DepHandler
}

/******************************************************************************/
// api for integration with joiners

// to use dep system from joiners:
// Values[(GroupId, DepOuterRequest)] ... yield outer.innerRequest.srcId → outer //todo ret pair
// @by[GroupId] Values[DepResponse] ...

// to implement dep handler using joiners:
// Values[DepInnerRequest]
// Values[(SrcId, DepResponse)]

case class DepInnerRequest(srcId: SrcId, request: DepRequest) //TODO Store serialized version
case class DepOuterRequest(srcId: SrcId, innerRequest: DepInnerRequest, parentSrcId: SrcId)
trait DepResponse extends Product {
  def innerRequest: DepInnerRequest
  def value: Option[_]
}
trait DepReqRespFactory extends Product { //todo 2 fac
  def outerRequest(parentId: SrcId)(rq: DepRequest): DepOuterRequest
  def response(req: DepInnerRequest, value: Option[_]): DepResponse
}

/******************************************************************************/
// api for accessing world by pk

trait AbstractAskByPK
trait AskByPK[A<:Product] extends AbstractAskByPK {
  def ask: SrcId ⇒ Dep[Values[A]]
}
trait AskByPKFactory {
  def forClass[A<:Product](cl: Class[A]): AskByPK[A]
}