package ee.cone.c4actor

import ee.cone.c4actor.MetaAttrProtocol.D_TxTransformNameMeta
import ee.cone.c4di.c4

@c4("ActivateContextApp") final class ActivateContext(
  getTxTransform: GetByPK[TxTransform]
) {
  def apply(local: Context): Context = {
    val txTransforms = getTxTransform.ofA(local).values
    txTransforms.foldLeft(local)((oldLocal, transform) =>
      transform.transform(TxTransformOrigMeta(transform.getClass.getName)(oldLocal)))
  }

  def main(args: Array[String]): Unit = {
    val list = List(1)
    println(list.nonEmpty, list.tail.isEmpty)
  }
}
