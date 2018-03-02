package ee.cone.c4actor.dep

import java.util.UUID

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.CtxType.ContextId
import ee.cone.c4actor.dep.request.ByClassNameRequestProtocol.ByClassNameRequest
import ee.cone.c4actor.dep.request.ByPKRequestProtocol.ByPKRequest
import ee.cone.c4actor.dep.request.ContextIdRequestProtocol.ContextIdRequest
import ee.cone.c4gate.SessionAttr
import ee.cone.c4gate.SessionDataProtocol.RawSessionData
import ee.cone.c4proto.{HasId, ToByteString}
import okio.ByteString

trait CommonRequestUtility {
  def askByClassName[A](Class: Class[A], from: Int, to: Int): Dep[List[A]]

  def askByPK[A](Class: Class[A], srcId: SrcId): Dep[Option[A]]

  def askContextId: Dep[ContextId]

  def askSessionAttr[P <: Product](attr: SessionAttr[P]): Dep[Option[Access[P]]]
}

trait CommonRequestUtilityMix extends SessionAttrAskMix {
  def askByClassName[A](Class: Class[A], from: Int, to: Int): Dep[List[A]] = new RequestDep[List[A]](ByClassNameRequest(Class.getName, from, to))

  def askByPK[A](Class: Class[A], srcId: SrcId): Dep[Option[A]] = new RequestDep[Option[A]](ByPKRequest(Class.getName, srcId))

  def askContextId: Dep[ContextId] = new RequestDep[ContextId](ContextIdRequest())
}

trait SessionAttrAskMix extends CommonRequestUtility with ModelAccessFactoryAppUndef {
  def qAdapterRegistry: QAdapterRegistry

  def defaultModelRegistry: DefaultModelRegistry

  def askSessionAttr[P <: Product](attr: SessionAttr[P]): Dep[Option[Access[P]]] = {
    val adapter: ProtoAdapter[Product] with HasId = qAdapterRegistry.byName(classOf[RawSessionData].getName)

    def genPK(request: RawSessionData): String =
      UUID.nameUUIDFromBytes(adapter.encode(request)).toString

    val lens = ProdLens[RawSessionData, P](attr.metaList)(
      rawData ⇒ qAdapterRegistry.byId(rawData.valueTypeId).decode(rawData.value).asInstanceOf[P],
      value ⇒ rawData ⇒ {
        val valueAdapter = qAdapterRegistry.byName(attr.className)
        val byteString = ToByteString(valueAdapter.encode(value))
        rawData.copy(valueTypeId = valueAdapter.id, value = byteString)
      }
    )

    def rawSessionData: ContextId ⇒ RawSessionData = contextId ⇒
      RawSessionData(
        srcId = "",
        sessionKey = contextId,
        domainSrcId = attr.pk,
        fieldId = attr.id,
        valueTypeId = 0,
        value = ByteString.EMPTY
      )

    for {
      contextId ← askContextId
      rawModel ← askByPK(classOf[RawSessionData], genPK(rawSessionData(contextId)))
    } yield {
      val request = rawSessionData(contextId)
      val pk = genPK(request)

      val value = rawModel.getOrElse({
        val model = defaultModelRegistry.get[P](attr.className).create(pk)
        lens.set(model)(request.copy(srcId = pk))
      }
      )
      modelAccessFactory.to(value).map(_.to(lens))
    }
  }
}