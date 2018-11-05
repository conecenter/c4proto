package ee.cone.c4actor

import ee.cone.c4actor.OrigMetaAttrProtocol.TxTransformNameMeta

object ActivateContext {
  def apply(local: Context): Context = {
    val txTransforms = ByPK(classOf[TxTransform]).of(local).values
    txTransforms.foldLeft(local)((oldLocal, transform) ⇒
      transform.transform(TxTransformOrigMeta(transform.getClass.getName)(oldLocal)))
  }

  def main(args: Array[String]): Unit = {
    val list = List(1)
    println(list.nonEmpty, list.tail.isEmpty)
  }
}
