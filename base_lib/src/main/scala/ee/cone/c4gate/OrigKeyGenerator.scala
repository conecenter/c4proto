package ee.cone.c4gate

import ee.cone.c4actor.IdGenUtil
import ee.cone.c4proto._

trait KeyGenerator {
  def idGenUtil: IdGenUtil

  def genPK[P <: Product](model: P, adapter: ProtoAdapter[Product] with HasId): String =
    idGenUtil.srcIdFromSerialized(adapter.id,ToByteString(adapter.encode(model)))
}
