package ee.cone.c4actor.dependancy

import java.util.UUID

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dependancy.SessionAttrRequestProtocol.SessionAttrRequest
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by, was}
import ee.cone.c4gate.SessionAttr
import ee.cone.c4gate.SessionDataProtocol.RawSessionData
import ee.cone.c4proto._
import okio.ByteString
import SessionAttrRequestUtility._
import ee.cone.c4actor.dependancy.ByPKUtils.askByPK
import ee.cone.c4gate.SessionDataProtocol

trait SessionAttributeRequestHandlerApp extends AssemblesApp with ProtocolsApp with RichDataApp with RequestHandlerRegistryApp {
  def sessionAttrs: List[SessionAttr[_ <: Product]] = Nil

  override def protocols: List[Protocol] = SessionAttrRequestProtocol :: SessionDataProtocol :: super.protocols

  override def handlers: List[RequestHandler[_]] = sessionAttrs.map(SessionAttributeRequestGenericHandler(_)(qAdapterRegistry, defaultModelRegistry, ModelAccessFactoryImpl)) ::: super.handlers
}

case class SessionAttributeRequestGenericHandler[A <: Product](sessionAttr: SessionAttr[A])(registry: QAdapterRegistry, defaultModelRegistry: DefaultModelRegistry,  modelAccessFactory: ModelAccessFactory) extends RequestHandler[SessionAttrRequest] {
  override def canHandle: Class[SessionAttrRequest] = classOf[SessionAttrRequest]

  override def handle: SessionAttrRequest => Dep[Option[Access[A]]] = request ⇒ {
    val adapter: ProtoAdapter[Product] with HasId = registry.byName(classOf[RawSessionData].getName)
    def genPK(request: RawSessionData): String =
      UUID.nameUUIDFromBytes(adapter.encode(request)).toString
    val lens = ProdLens[RawSessionData,A](NameMetaAttr(request.sessionAttrId.toString) :: Nil)(
      rawData ⇒ registry.byId(rawData.valueTypeId).decode(rawData.value).asInstanceOf[A],
      value ⇒ rawData ⇒ {
        val valueAdapter = registry.byName(request.sessionAttrClassName)
        val byteString = ToByteString(valueAdapter.encode(value))
        rawData.copy(valueTypeId = valueAdapter.id, value = byteString)
      }
    )
    val rawSessionData: String ⇒ RawSessionData = sessionKey ⇒ RawSessionData(
      srcId = "",
      sessionKey = sessionKey,
      domainSrcId = request.sessionAttrPk,
      fieldId = request.sessionAttrId,
      valueTypeId = 0,
      value = ByteString.EMPTY
    )
    for {
      sessionKey ← askSessionKey
      value ← askByPK(classOf[RawSessionData], genPK(rawSessionData(sessionKey)))
    } yield {
      val kek: RawSessionData = value.getOrElse({
        val model = defaultModelRegistry.get[A](request.sessionAttrClassName).create(genPK(rawSessionData(sessionKey)))
        lens.set(model)(rawSessionData(sessionKey).copy(srcId=genPK(rawSessionData(sessionKey))))
      })
      modelAccessFactory.to(kek).map(_.to(lens))
    }
  }

  def askSessionKey = new ResolvedDep[String]("test")
}

@protocol object SessionAttrRequestProtocol extends Protocol {

  @Id(0x0f2d) case class SessionAttrRequest(
    @Id(0x0f2e) sessionAttrPk: String,
    @Id(0x0f2f) sessionAttrId: Long,
    @Id(0x0f30) sessionAttrClassName: String
  )

}

object SessionAttrRequestUtility{
  def genPK(sessionAttr: SessionAttr[_], adapter: ProtoAdapter[Product] with HasId, sessionKey: String): String = {
    val request = RawSessionData(srcId = "", sessionKey, sessionAttr.pk, sessionAttr.id, valueTypeId = 0, value = ByteString.EMPTY)
    UUID.nameUUIDFromBytes(adapter.encode(request)).toString
  }

  def askSessionAttr[A](sessionAttr: SessionAttr[A]) = new RequestDep[Option[Access[A]]](SessionAttrRequest(sessionAttr.pk, sessionAttr.id, sessionAttr.className))
}

//
//
//
//@Id(0x0f30)