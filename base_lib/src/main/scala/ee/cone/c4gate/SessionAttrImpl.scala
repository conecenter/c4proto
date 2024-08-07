package ee.cone.c4gate

import java.util.UUID

import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, CallerAssemble, assemble, by, c4assemble}
import ee.cone.c4gate.AlienProtocol.U_FromAlienState
import ee.cone.c4gate.SessionDataProtocol.{N_RawDataNode, U_RawSessionData}
import ee.cone.c4proto._
import okio.ByteString
import ee.cone.c4di.{c4, provide}

@protocol("SessionDataProtocolApp") object SessionDataProtocol {
  @Id(0x0066) case class U_RawSessionData(
    @Id(0x0061) srcId: String,
    @Id(0x0067) sessionKey: String,
    @Id(0x0a66) dataNode: Option[N_RawDataNode] // Always isDefined
  )

  @Id(0x0a65) case class N_RawDataNode(
    @Id(0x0068) domainSrcId: String,
    @Id(0x0069) fieldId: Long,
    @Id(0x0064) valueTypeId: Long,
    @Id(0x0065) value: okio.ByteString
  )
}

@c4("SessionAttrCompApp") final class SessionDataAssemblesBase(mortal: MortalFactory) {
  @provide def subAssembles: Seq[Assemble] = List(mortal(classOf[U_RawSessionData]))
}

@c4assemble("SessionAttrCompApp") class SessionDataAssembleBase {
  type SessionKey = SrcId

  def joinBySessionKey(
    srcId: SrcId,
    sd: Each[U_RawSessionData]
  ): Values[(SessionKey, U_RawSessionData)] = List(sd.sessionKey -> sd)

  def joinSessionLife(
    key: SrcId,
    session: Each[U_FromAlienState],
    @by[SessionKey] sessionData: Each[U_RawSessionData]
  ): Values[(Alive, U_RawSessionData)] = List(WithPK(sessionData))
}

object SessionAttrLens {
  def apply[From <: Product, To <: Product](
    metaList: List[AbstractMetaAttr],
    fromMeta: OrigMeta[From],
    toMeta: OrigMeta[To]
  )(of: From => To, set: To => From => From): ProdLens[From, To] =
    CreateProdLens.check(ProdLensStrict(metaList, fromMeta.typeKey, toMeta.typeKey)(fromMeta.cl, toMeta.cl, of, set))
}

@c4("SessionAttrCompApp") final class SessionAttrAccessFactoryImpl(
  registry: QAdapterRegistry,
  modelFactory: ModelFactory,
  modelAccessFactory: RModelAccessFactory,
  val idGenUtil: IdGenUtil,
  getU_RawSessionData: GetByPK[U_RawSessionData],
  rawSessionDataMeta: OrigMeta[U_RawSessionData],
  origMetaRegistry: OrigMetaRegistry
) extends SessionAttrAccessFactory with KeyGenerator {
  def to[P <: Product](attr: SessionAttr[P]): Context => Access[P] = {
    val adapter = registry.byName(classOf[U_RawSessionData].getName)
    val meta = origMetaRegistry.byName(attr.className).asInstanceOf[OrigMeta[P]]
    val lens = SessionAttrLens(attr.metaList, rawSessionDataMeta, meta)(
      rawData => registry.byId(rawData.dataNode.get.valueTypeId).decode(rawData.dataNode.get.value).asInstanceOf[P],
      value => rawData => {
        val valueAdapter = registry.byName(attr.className)
        val byteString = ToByteString(valueAdapter.encode(value))
        val newDataNode = rawData.dataNode.get.copy(valueTypeId = valueAdapter.id, value = byteString)
        rawData.copy(dataNode = Option(newDataNode))
      }
    )
    local => {
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
      val pk = genPK(request, adapter) //val deepPK = genPK(request.copy(sessionKey=""))
      val value: U_RawSessionData = getU_RawSessionData.ofA(local).getOrElse(pk, {
        val model = modelFactory.create[P](attr.className)(pk)
        lens.set(model)(request.copy(srcId = pk))
      })
      modelAccessFactory.to(getU_RawSessionData, value).to(lens)
    }
  }
}


//todo user level with expiration
/*
@Id(0x0058) userName: String,
@Id(0x5473) until: Long
  def joinRaw(
    srcId: SrcId,
    rawDataValues: Values[U_RawSessionData]
  ): Values[(SrcId, SessionData)] = for {
    r <- rawDataValues
    adapter <- registry.byId.get(r.valueTypeId)
  } yield WithPK(SessionData(r.srcId, r, adapter.decode(r.value.toByteArray)))

  def joinUserLife(
    key: SrcId,
    @by[SessionKey] sessionDataValues: Values[U_RawSessionData]
  ): Values[(Alive, U_RawSessionData)] = for {
    sessionData <- sessionDataValues if sessionData.sessionKey.isEmpty
  } yield WithPK(sessionData)

case class SessionData(srcId: String, orig: U_RawSessionData, value: Product)
*/

