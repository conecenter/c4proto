package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId

trait ModelFactory {
  def create[P<:Product](valueClass: Class[P])(srcId: SrcId): P = process[P](valueClass.getName, None, srcId)
  def create[P<:Product](className: String)(srcId: SrcId): P = process[P](className, None, srcId)
  def changeSrcId[P<:Product](valueClass: Class[P])(srcId: SrcId)(model: P): P = change[P](valueClass.getName, model, srcId)
  def changeSrcId[P<:Product](className: String)(srcId: SrcId)(model: P): P = change[P](className, model, srcId)
  protected def change[P<:Product](className: String, base: P, srcId: SrcId): P
  protected def process[P<:Product](className: String, basedOn: Option[P], srcId: SrcId): P
}

abstract class GeneralDefaultModelInitializer(val valueClass: Class[_], specInit: Nothing=>Any) {
  def init[T](value: T): T = specInit.asInstanceOf[T=>T](value)
}
abstract class DefaultModelInitializer[P](valueClass: Class[P], init: P=>P) extends GeneralDefaultModelInitializer(valueClass,init)
