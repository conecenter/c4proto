package ee.cone.c4actor.dep

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

import ee.cone.c4actor.QAdapterRegistry
import ee.cone.c4actor.dep.CtxType.DepRequest
import ee.cone.c4actor.Types.SrcId

trait DepAssembleUtility {
  def generatePK(rq: DepRequest, adapterRegistry: QAdapterRegistry): SrcId

  def stringToKey(value: String): SrcId
}

trait DepAssembleUtilityImpl extends DepAssembleUtility {
  def generatePK(rq: DepRequest, adapterRegistry: QAdapterRegistry): SrcId = { //TODO ilya custom serializer
    val valueAdapter = adapterRegistry.byName(rq.getClass.getName)
    val bytes = valueAdapter.encode(rq)
    UUID.nameUUIDFromBytes(toBytes(valueAdapter.id) ++ bytes).toString
  }

  private def toBytes(value: Long) =
    ByteBuffer.allocate(java.lang.Long.BYTES).putLong(value).array()

  def stringToKey(value: String) =
    UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString
}
