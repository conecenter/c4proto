package ee.cone.c4gate

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{Context, GetByPK, IdGenUtil, LEvent, QAdapterRegistry}
import ee.cone.c4di.c4
import ee.cone.c4gate.SessionDataProtocol.{N_RawDataNode, U_RawSessionData}
import ee.cone.c4proto.{HasId, ProtoAdapter, ToByteString}
import okio.ByteString


@c4("SessionAttrCompApp") final class RawSessionAttrUtilsImpl(
  val idGenUtil: IdGenUtil,
  getU_RawSessionData: GetByPK[U_RawSessionData],
  qAdapterRegistry: QAdapterRegistry,
) extends KeyGenerator with RawSessionAttrUtils {
  lazy val rawSessionDataAdapter: ProtoAdapter[Product] with HasId = qAdapterRegistry.byName(classOf[U_RawSessionData].getName)

  def getAttrValue(sessionKey: String, pk: SrcId, attrId: Long, local: Context): Option[Product] = {
    val stubDataNode = N_RawDataNode(
      domainSrcId = pk,
      fieldId = attrId,
      valueTypeId = 0,
      value = ByteString.EMPTY,
    )
    val stubRawSessionData = U_RawSessionData(
      srcId = "",
      sessionKey = sessionKey,
      dataNode = Option(stubDataNode),
    )
    val nPK = genPK(stubRawSessionData, rawSessionDataAdapter)
    getU_RawSessionData.ofA(local).get(nPK) match {
      case None => None
      case Some(rawSessionData) =>
        val dataNode = rawSessionData.dataNode.get
        val valueAdapterOpt = qAdapterRegistry.byId.get(dataNode.valueTypeId)
        valueAdapterOpt match {
          case None => None
          case Some(adapter) => Some(adapter.decode(dataNode.value))
        }
    }
  }

  def setAttrValue[P <: Product](sessionKey: String, pk: SrcId, attrId: Long, value: Option[P]): Seq[LEvent[Product]] = {
    val stubDataNode = N_RawDataNode(
      domainSrcId = pk,
      fieldId = attrId,
      valueTypeId = 0,
      value = ByteString.EMPTY
    )
    val stubRawSessionData: U_RawSessionData = U_RawSessionData(
      srcId = "",
      sessionKey = sessionKey,
      dataNode = Option(stubDataNode)
    )
    val nPK = genPK(stubRawSessionData, rawSessionDataAdapter)
    value match {
      case Some(v) =>
        val valueAdapter = qAdapterRegistry.byName(v.getClass.getName)
        val encodedValue = ToByteString(valueAdapter.encode(v))
        val dataNode = stubDataNode.copy(valueTypeId = valueAdapter.id, value = encodedValue)
        val rawSessionData = stubRawSessionData.copy(srcId = nPK, dataNode = Option(dataNode))
        LEvent.update(rawSessionData)
      case None =>
        val rawSessionData = stubRawSessionData.copy(srcId = nPK)
        LEvent.delete(rawSessionData)
    }
  }
}
