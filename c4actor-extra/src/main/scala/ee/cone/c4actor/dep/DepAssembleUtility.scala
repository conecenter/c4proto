package ee.cone.c4actor.dep

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

import ee.cone.c4actor.QAdapterRegistry
import ee.cone.c4actor.dep.CtxType.DepRequest
import ee.cone.c4actor.Types.SrcId
import StandardCharsets.UTF_8

trait DepAssembleUtility {
  def qAdapterRegistry: QAdapterRegistry

  def generateDepOuterRequest(rq: DepRequest, parentId: SrcId): DepOuterRequest

  def generatePK(rq: DepRequest): SrcId


}

trait DepGenericUtility {
  def stringToKey(value: String): SrcId

  def toBytes(value: Long): Array[Byte]
}

trait DepAssembleUtilityImpl extends DepGenericUtilityImpl with DepAssembleUtility {

  def generateDepOuterRequest(rq: DepRequest, parentId: SrcId): DepOuterRequest = {
    val inner = generateDepInnerRequest(rq)
    val srcId = generatePKFromTwoSrcId(parentId, inner.srcId)
    DepOuterRequest(srcId, inner, parentId)
  }

  def generateDepInnerRequest(rq: DepRequest): DepInnerRequest = {
    val srcId = generatePK(rq)
    DepInnerRequest(srcId, rq)
  }

  def generatePK(rq: DepRequest): SrcId = {
    val valueAdapter = qAdapterRegistry.byName(rq.getClass.getName)
    val bytes = valueAdapter.encode(rq)
    UUID.nameUUIDFromBytes(toBytes(valueAdapter.id) ++ bytes).toString
  }

  def generatePKFromTwoSrcId(a: SrcId, b: SrcId): SrcId = {
    UUID.nameUUIDFromBytes(a.getBytes(UTF_8) ++ b.getBytes(UTF_8)).toString
  }

  def generatePKWithParent(rq: DepRequest, parent: String): SrcId = {
    val innerSrcId = generatePK(rq, qAdapterRegistry)
    val parentBytes = parent.getBytes(UTF_8)
    UUID.nameUUIDFromBytes(innerSrcId.getBytes(UTF_8) ++ parentBytes).toString
  }
}

trait DepGenericUtilityImpl extends DepGenericUtility {
  def toBytes(value: Long): Array[Byte] =
    ByteBuffer.allocate(java.lang.Long.BYTES).putLong(value).array()

  def stringToKey(value: String): SrcId =
    UUID.nameUUIDFromBytes(value.getBytes(UTF_8)).toString
}
