package ee.cone.c4actor

object ActivateContext {
  def apply(local: Context): Context = {
    val txTransforms = ByPK(classOf[TxTransform]).of(local).values
    txTransforms.foldLeft(local)((oldLocal, transform) ⇒ ApplyTransformWithMeta(transform)(oldLocal))
  }
}
