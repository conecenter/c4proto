package ee.cone.c4actor.dep

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.DepTypes.{DepCtx, DepRequest}
import ee.cone.c4assemble.Types.Values

import scala.collection.immutable.{Seq,Map}

/******************************************************************************/
// general api for code that uses and returns Dep-s

case class Resolvable[+A](value: Option[A], requests: Seq[DepRequest] = Nil)  // low-level //?hashed

trait Dep[A] {
  def flatMap[B](f: A ⇒ Dep[B]): Dep[B]
  def map[B](f: A ⇒ B): Dep[B]
  // def filter(p: A ⇒ Boolean): Dep[A] use result ← Dep; (a, b) = result instead
  def resolve(ctx: DepCtx): Resolvable[A] // low-level
}

object DepTypes {
  type DepRequest = Product
  type DepCtx = Map[DepRequest, _] // low-level
  type GroupId = SrcId
  type ParentId = SrcId
  type AddId = SrcId
}

trait DepFactory extends Product {
  def parallelTuple[A, B](a: Dep[A], b: Dep[B]): Dep[(A, B)]
  def parallelSeq[A](value: Seq[Dep[A]]): Dep[Seq[A]]
  def uncheckedRequestDep[Out](request: DepRequest): Dep[Out] // low-level; try to use more high-level DepAsk instead of this unchecked version
  def resolvedRequestDep[Out](response: Out): Dep[Out]
  def parOptSeq[A](value: Seq[Dep[A]]): Dep[Seq[Option[A]]]
  def parUnsafeSeq[A](value: Seq[Dep[A]]): Dep[Seq[A]]
  def optDep[A](value: Dep[A]): Dep[Option[A]]
}

/******************************************************************************/

// api for type-safe dep-request asking/handling

trait DepHandler extends Product

trait DepAsk[RequestIn <: Product, Out] extends Product {
  def ask: RequestIn ⇒ Dep[Out]

  def by(handler: RequestIn ⇒ Dep[Out]): DepHandler

  def byParent[ReasonIn <: Product](reason: DepAsk[ReasonIn, _], handler: ReasonIn ⇒ Map[RequestIn, Out]): DepHandler
}
trait DepAskFactory extends Product {
  def forClasses[RequestIn <: Product, Out](in: Class[RequestIn], out: Class[Out]): DepAsk[RequestIn, Out]
}

// api for integration with joiners

// to use dep system from joiners:
// Values[(GroupId, DepOuterRequest)] ... yield depOuterRequestFactory.tupled(parentId)(rq)
// @by[GroupId] Values[DepResponse] ...

// to implement dep handler using joiners:
// Values[DepInnerRequest]
// Values[(SrcId, DepResponse)]

case class DepInnerRequest(srcId: SrcId, request: DepRequest) //TODO Store serialized version

case class DepUnresolvedRequest(srcId: SrcId, request: DepRequest, responses: Int, parents: List[SrcId])

case class DepOuterRequest(srcId: SrcId, innerRequest: DepInnerRequest, parentSrcId: SrcId)
trait DepOuterRequestFactory extends Product {
  def tupled(parentId: SrcId)(rq: DepRequest): (SrcId,DepOuterRequest)
  def innerRequest(rq: DepRequest): DepInnerRequest
}

trait DepResponseForwardFilter {
  def parentCl: Option[Class[_ <: Product]]
  def childCl: Class[_ <: Product]
  def filter: DepResponse ⇒ Option[DepResponse]
}

trait DepResponseFilterFactory {
  def withParent(parentCl: Class[_ <: Product], childCl: Class[_ <: Product]): (DepResponse ⇒ Option[DepResponse]) ⇒ DepResponseForwardFilter
  def withChild(childCl: Class[_ <: Product]): (DepResponse ⇒ Option[DepResponse]) ⇒ DepResponseForwardFilter
}

trait DepResponse extends Product {
  def innerRequest: DepInnerRequest
  def value: Option[_]
}
trait DepResponseFactory extends Product {
  def wrap(req: DepInnerRequest, value: Option[_]): DepResponse
}

/******************************************************************************/
// api for accessing world by pk

trait AbstractAskByPK
trait AskByPK[A<:Product] extends AbstractAskByPK {
  def list(id: SrcId): Dep[List[A]]
  def option(id: SrcId): Dep[Option[A]]
}
trait AskByPKFactory {
  def forClass[A<:Product](cl: Class[A]): AskByPK[A]
}