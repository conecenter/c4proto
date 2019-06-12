package ee.cone.c4gate

import java.util.UUID

import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble, by}
import ee.cone.c4gate.AlienProtocol.FromAlienState
import ee.cone.c4gate.SessionDataProtocol.{N_RawDataNode, U_RawSessionData}
import ee.cone.c4proto._
import okio.ByteString

case object SessionAttrCat extends DataCategory

@protocol(SessionAttrCat) object SessionDataProtocolBase   {
  @Id(0x0066) case class U_RawSessionData(
    @Id(0x0061) srcId: String,
    @Id(0x0067) sessionKey: String,
    @Id(0x0a66) dataNode: Option[N_RawDataNode] // Always isDefined
  )

  @Cat(InnerCat)
  @Id(0x0a65) case class N_RawDataNode(
    @Id(0x0068) domainSrcId: String,
    @Id(0x0069) fieldId: Long,
    @Id(0x0064) valueTypeId: Long,
    @Id(0x0065) value: okio.ByteString
  )
}

object SessionDataAssembles {
  def apply(mortal: MortalFactory): List[Assemble] =
    mortal(classOf[U_RawSessionData]) :: new SessionDataAssemble :: Nil
}

@assemble class SessionDataAssembleBase   {
  type SessionKey = SrcId

  def joinBySessionKey(
    srcId: SrcId,
    sd: Each[U_RawSessionData]
  ): Values[(SessionKey, U_RawSessionData)] = List(sd.sessionKey → sd)

  def joinSessionLife(
    key: SrcId,
    session: Each[FromAlienState],
    @by[SessionKey] sessionData: Each[U_RawSessionData]
  ): Values[(Alive, U_RawSessionData)] = List(WithPK(sessionData))
}

class SessionAttrAccessFactoryImpl(
  registry: QAdapterRegistry,
  defaultModelRegistry: DefaultModelRegistry,
  modelAccessFactory: ModelAccessFactory,
  val idGenUtil: IdGenUtil
) extends SessionAttrAccessFactory with KeyGenerator{
  def to[P<:Product](attr: SessionAttr[P]): Context⇒Option[Access[P]] = {
    val adapter = registry.byName(classOf[U_RawSessionData].getName)
    val lens = ProdLens[U_RawSessionData,P](attr.metaList)(
      rawData ⇒ registry.byId(rawData.dataNode.get.valueTypeId).decode(rawData.dataNode.get.value).asInstanceOf[P],
      value ⇒ rawData ⇒ {
        val valueAdapter = registry.byName(attr.className)
        val byteString = ToByteString(valueAdapter.encode(value))
        val newDataNode = rawData.dataNode.get.copy(valueTypeId = valueAdapter.id, value = byteString)
        rawData.copy(dataNode = Option(newDataNode))
      }
    )
    val byPK = ByPK(classOf[U_RawSessionData])
    local ⇒ {
      val sessionKey = CurrentSessionKey.of(local)
      val request: U_RawSessionData = U_RawSessionData(
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
      val pk = genPK(request, adapter)//val deepPK = genPK(request.copy(sessionKey=""))
      val value: U_RawSessionData = byPK.of(local).getOrElse(pk,{
        val model = defaultModelRegistry.get[P](attr.className).create(pk)
        lens.set(model)(request.copy(srcId=pk))
      })
      modelAccessFactory.to(value).map(_.to(lens))
    }
  }
}


//todo user level with expiration
/*
@Id(0x0058) userName: String,
@Id until: Long
  def joinRaw(
    srcId: SrcId,
    rawDataValues: Values[U_RawSessionData]
  ): Values[(SrcId, SessionData)] = for {
    r ← rawDataValues
    adapter ← registry.byId.get(r.valueTypeId)
  } yield WithPK(SessionData(r.srcId, r, adapter.decode(r.value.toByteArray)))

  def joinUserLife(
    key: SrcId,
    @by[SessionKey] sessionDataValues: Values[U_RawSessionData]
  ): Values[(Alive, U_RawSessionData)] = for {
    sessionData ← sessionDataValues if sessionData.sessionKey.isEmpty
  } yield WithPK(sessionData)

case class SessionData(srcId: String, orig: U_RawSessionData, value: Product)
*/

