package ee.cone.c4actor.dependancy

import ee.cone.c4actor.CtxType.Ctx
import ee.cone.c4actor.{AbstractLens, Lens}
import ee.cone.c4actor.Types.SrcId

trait Request extends Product {
  val srcId: SrcId
  val prevSrcId: List[SrcId]

  def extendPrev(id: SrcId): Request
}

trait DepRequest[A] extends Lens[Ctx, Option[A]] with Request

abstract class AbstractDepRequest[A] extends AbstractLens[Ctx, Option[A]] with DepRequest[A] with Product {
  def of: Ctx ⇒ Option[A] = ctx ⇒ ctx.getOrElse(this.srcId, None).asInstanceOf[Option[A]]

  def set: Option[A] ⇒ Ctx ⇒ Ctx = value ⇒ ctx ⇒ ctx + (this.srcId → value)
}