package ee.cone.c4gate

import java.util.UUID

import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4gate.AlienProtocol.FromAlienState
import ee.cone.c4gate.SessionDataProtocol.RawSessionData
import ee.cone.c4proto._
import okio.ByteString

@protocol object SessionDataProtocol extends Protocol {
  @Id(0x0066) case class RawSessionData(
    @Id(0x0061) srcId: String,
    @Id(0x0067) sessionKey: String,
    @Id(0x0068) domainSrcId: String,
    @Id(0x0069) fieldId: Long,
    @Id(0x0064) valueTypeId: Long,
    @Id(0x0065) value: okio.ByteString
  )
}

@c4component @listed case class SessionDataAssembles() extends Mortal(classOf[RawSessionData])

@c4component @listed @assemble case class SessionDataAssemble() extends Assemble {
  type SessionKey = SrcId

  def joinBySessionKey(
    srcId: SrcId,
    sessionDataValues: Values[RawSessionData]
  ): Values[(SessionKey, RawSessionData)] = for {
    sd ← sessionDataValues
  } yield sd.sessionKey → sd

  def joinSessionLife(
    key: SrcId,
    sessions: Values[FromAlienState],
    @by[SessionKey] sessionDataValues: Values[RawSessionData]
  ): Values[(Alive, RawSessionData)] = for {
    session ← sessions
    sessionData ← sessionDataValues
  } yield WithPK(sessionData)
}

@c4component case class SessionAttrAccessFactoryImpl(
  registry: QAdapterRegistry,
  defaultModelRegistry: DefaultModelRegistry,
  modelAccessFactory: ModelAccessFactory
) extends SessionAttrAccessFactory {
  def to[P<:Product](attr: SessionAttr[P]): Context⇒Option[Access[P]] = {
    val adapter = registry.byName(classOf[RawSessionData].getName)
    def genPK(request: RawSessionData): String =
      UUID.nameUUIDFromBytes(adapter.encode(request)).toString
    val lens = ProdLens[RawSessionData,P](attr.metaList)(
      rawData ⇒ registry.byId(rawData.valueTypeId).decode(rawData.value).asInstanceOf[P],
      value ⇒ rawData ⇒ {
        val valueAdapter = registry.byName(attr.className)
        val byteString = ToByteString(valueAdapter.encode(value))
        rawData.copy(valueTypeId = valueAdapter.id, value = byteString)
      }
    )
    val byPK = ByPK(classOf[RawSessionData])
    local ⇒ {
      val sessionKey = CurrentSessionKey.of(local)
      val request: RawSessionData = RawSessionData(
        srcId = "",
        sessionKey = sessionKey,
        domainSrcId = attr.pk,
        fieldId = attr.id,
        valueTypeId = 0,
        value = ByteString.EMPTY
      )
      val pk = genPK(request)//val deepPK = genPK(request.copy(sessionKey=""))
      val value = byPK.of(local).getOrElse(pk,{
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
    rawDataValues: Values[RawSessionData]
  ): Values[(SrcId, SessionData)] = for {
    r ← rawDataValues
    adapter ← registry.byId.get(r.valueTypeId)
  } yield WithPK(SessionData(r.srcId, r, adapter.decode(r.value.toByteArray)))

  def joinUserLife(
    key: SrcId,
    @by[SessionKey] sessionDataValues: Values[RawSessionData]
  ): Values[(Alive, RawSessionData)] = for {
    sessionData ← sessionDataValues if sessionData.sessionKey.isEmpty
  } yield WithPK(sessionData)

case class SessionData(srcId: String, orig: RawSessionData, value: Product)
*/

