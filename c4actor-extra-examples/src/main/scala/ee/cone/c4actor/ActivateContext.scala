package ee.cone.c4actor

object ActivateContext {
  def apply(local: Context): Context = {
    val txTransforms = ByPK(classOf[TxTransform]).of(local).values
    txTransforms.foldLeft(local)((oldLocal, transform) ⇒
      transform.transform(TxTransformDescription.set(transform.description)(oldLocal)))
  }

  def main(args: Array[String]): Unit = {
    val list = List(1)
    println(list.nonEmpty, list.tail.isEmpty)
  }
}
