package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId

trait ModelFactory {
  def create[P<:Product](valueClass: Class[P])(srcId: SrcId): P = process[P](valueClass.getName, None, srcId)
  def create[P<:Product](className: String)(srcId: SrcId): P = process[P](className, None, srcId)
  def changeSrcId[P<:Product](valueClass: Class[P])(srcId: SrcId)(model: P): P = process[P](valueClass.getName, Option(model), srcId)
  def changeSrcId[P<:Product](className: String)(srcId: SrcId)(model: P): P = process[P](className, Option(model), srcId)
  protected def process[P<:Product](className: String, basedOn: Option[P], srcId: SrcId): P
}

abstract class DefaultModelInitializer[P](val valueClass: Class[P], val init: P=>P)

