package ee.cone.c4gate

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.UUIDUtil
import ee.cone.c4proto.{HasId, ToByteString}

trait OrigKeyGenerator {
  def uuidUtil: UUIDUtil

  def genPK[P <: Product](model: P, adapter: ProtoAdapter[Product] with HasId): String =
    uuidUtil.srcIdFromSerialized(adapter.id,ToByteString(adapter.encode(model)))
}
