package ee.cone.c4actor.dep

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.DepTypes.{DepCtx, DepRequest}
import ee.cone.c4assemble.Types.Values

import scala.collection.immutable.{Map, Seq}

/******************************************************************************/
// general api for code that uses and returns Dep-s

case class Resolvable[+A](value: Option[A], requests: Seq[DepRequest] = Nil)  // low-level //?hashed

trait Dep[A] {
  def flatMap[B](f: A ⇒ Dep[B]): Dep[B]
  def map[B](f: A ⇒ B): Dep[B]
  def resolve(ctx: DepCtx): Resolvable[A] // low-level
}

object DepTypes {
  type DepRequest = Product
  type DepCtx = Map[DepRequest, _] // low-level
  type GroupId = SrcId
}

trait DepFactory extends Product {
  def parallelSeq[A](value: Seq[Dep[A]]): Dep[Seq[A]]
  def uncheckedRequestDep[Out](request: DepRequest): Dep[Out] // low-level; try to use more high-level DepAsk instead of this unchecked version
}

/******************************************************************************/

// api for type-safe dep-request asking/handling

trait DepHandler extends Product {
  def requestClassName: String
}

abstract class AddDepHandlerApi[RequestIn <: DepRequest, ReasonIn <: DepRequest](
  val requestInCl: Class[RequestIn], val reasonInCl: Class[ReasonIn]
) extends DepHandler {
  def add: ReasonIn ⇒ Map[RequestIn, _]
}

abstract class ByDepHandlerApi[RequestIn <: DepRequest](
  val requestInCl: Class[RequestIn]
) extends DepHandler {
  def handle: RequestIn ⇒ Dep[_]
}

trait DepAsk[In<:Product,Out] extends Product {
  def ask: In ⇒ Dep[Out]

  def by(handler: In ⇒ Dep[Out]): ByDepHandlerApi[In]

  def add[ReasonIn <: Product](reason: DepAsk[ReasonIn, _], handler: ReasonIn ⇒ Map[In, Out]): AddDepHandlerApi[In, ReasonIn]
}
trait DepAskFactory extends Product {
  def forClasses[In<:Product,Out](in: Class[In], out: Class[Out]): DepAsk[In,Out]
}

// api for integration with joiners

// to use dep system from joiners:
// Values[(GroupId, DepOuterRequest)] ... yield depOuterRequestFactory.tupled(parentId)(rq)
// @by[GroupId] Values[DepResponse] ...

// to implement dep handler using joiners:
// Values[DepInnerRequest]
// Values[(SrcId, DepResponse)]

case class DepInnerRequest(srcId: SrcId, request: DepRequest) //TODO Store serialized version

case class DepOuterRequest(srcId: SrcId, innerRequest: DepInnerRequest, parentSrcId: SrcId)
trait DepOuterRequestFactory extends Product {
  def tupled(parentId: SrcId)(rq: DepRequest): (SrcId,DepOuterRequest)
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
  def seq(id: SrcId): Dep[Values[A]]
  def option(id: SrcId): Dep[Option[A]]
}
trait AskByPKFactory {
  def forClass[A<:Product](cl: Class[A]): AskByPK[A]
}