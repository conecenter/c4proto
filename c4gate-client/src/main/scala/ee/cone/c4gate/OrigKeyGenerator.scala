package ee.cone.c4gate

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.IdGenUtil
import ee.cone.c4proto.{HasId, ToByteString}

trait KeyGenerator {
  def idGenUtil: IdGenUtil

  def genPK[P <: Product](model: P, adapter: ProtoAdapter[Product] with HasId): String =
    idGenUtil.srcIdFromSerialized(adapter.id,ToByteString(adapter.encode(model)))
}
