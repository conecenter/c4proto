package ee.cone.c4gate

import ee.cone.c4actor.{Context, GetByPK, IdGenUtil, LEvent, QAdapterRegistry}
import ee.cone.c4di.c4
import ee.cone.c4gate.SessionDataProtocol.{N_RawDataNode, U_RawSessionData}
import ee.cone.c4proto.{HasId, ProtoAdapter}
import okio.ByteString


@c4("SessionAttrCompApp") final class RawSessionDataUtils(
  val idGenUtil: IdGenUtil,
  getU_RawSessionData: GetByPK[U_RawSessionData],
  qAdapterRegistry: QAdapterRegistry,
) extends KeyGenerator {
  lazy val adapter: ProtoAdapter[Product] with HasId = qAdapterRegistry.byName(classOf[U_RawSessionData].getName)
  def getDataNode[P <: Product](sessionKey: String, attr: SessionAttr[P], local: Context): Option[N_RawDataNode] = {
    val rawSessionData: U_RawSessionData = U_RawSessionData(
      srcId = "",
      sessionKey = sessionKey,
      dataNode = Option(
        N_RawDataNode(
          domainSrcId = attr.pk,
          fieldId = attr.id,
          valueTypeId = 0,
          value = ByteString.EMPTY
        )
      )
    )
    val pk = genPK(rawSessionData, adapter)
    getU_RawSessionData.ofA(local).get(pk).flatMap(_.dataNode)
  }
  def updateDataNodeLEvents[P <: Product](sessionKey: String, newValue: N_RawDataNode): Seq[LEvent[Product]] = {
    val rawSessionData: U_RawSessionData = U_RawSessionData(
      srcId = "",
      sessionKey = sessionKey,
      dataNode = Some(newValue)
    )
    val pk = genPK(rawSessionData, adapter)
    LEvent.update(rawSessionData.copy(srcId = pk))
  }
}
