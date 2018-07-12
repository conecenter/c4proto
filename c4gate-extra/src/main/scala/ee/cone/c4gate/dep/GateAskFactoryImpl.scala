package ee.cone.c4gate.dep

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.ContextTypes.{ContextId, RoleId, UserId}
import ee.cone.c4actor.dep.{AskByPK, CommonRequestUtilityFactory, Dep}
import ee.cone.c4actor.dep_impl.RequestDep
import ee.cone.c4gate.SessionDataProtocol.{RawDataNode, RawSessionData}
import ee.cone.c4gate.deep_session.DeepSessionDataProtocol.{RawRoleData, RawUserData}
import ee.cone.c4gate.deep_session.{DeepRawSessionData, TxDeepRawDataLens, UserLevelAttr}
import ee.cone.c4actor.dep.request.CurrentTimeRequestProtocol.CurrentTimeRequest
import ee.cone.c4gate.{OrigKeyGenerator, SessionAttr}
import ee.cone.c4proto.{HasId, ToByteString}
import okio.ByteString

case class SessionAttrAskFactoryImpl(
  qAdapterRegistry: QAdapterRegistry,
  defaultModelRegistry: DefaultModelRegistry,
  modelAccessFactory: ModelAccessFactory,
  commonRequestFactory: CommonRequestUtilityFactory,
  rawDataAsk: AskByPK[RawSessionData],
  rawUserDataAsk: AskByPK[RawUserData],
  rawRoleDataAsk: AskByPK[RawRoleData],
  idGenUtil: IdGenUtil
) extends SessionAttrAskFactoryApi with OrigKeyGenerator {

  def askSessionAttr[P <: Product](attr: SessionAttr[P]): Dep[Option[Access[P]]] = {
    if (attr.metaList.collectFirst { case UserLevelAttr ⇒ "" }.isEmpty)
      oldAsk(attr)
    else
      newAsk(attr)
  }

  def oldAsk[P <: Product](attr: SessionAttr[P]): Dep[Option[Access[P]]] = {
    val adapter: ProtoAdapter[Product] with HasId = qAdapterRegistry.byName(classOf[RawSessionData].getName)

    val lens = ProdLens[RawSessionData, P](attr.metaList)(
      rawData ⇒ qAdapterRegistry.byId(rawData.dataNode.get.valueTypeId).decode(rawData.dataNode.get.value).asInstanceOf[P],
      value ⇒ rawData ⇒ {
        val valueAdapter = qAdapterRegistry.byName(attr.className)
        val byteString = ToByteString(valueAdapter.encode(value))
        val newDataNode = rawData.dataNode.get.copy(valueTypeId = valueAdapter.id, value = byteString)
        rawData.copy(dataNode = Option(newDataNode))
      }
    )

    def rawSessionData: ContextId ⇒ RawSessionData = contextId ⇒
      RawSessionData(
        srcId = "",
        sessionKey = contextId,
        dataNode = Option(
          RawDataNode(
            domainSrcId = attr.pk,
            fieldId = attr.id,
            valueTypeId = 0,
            value = ByteString.EMPTY
          )
        )
      )

    import commonRequestFactory._
    for {
      contextId ← askContextId
      rawModel ← rawDataAsk.option(genPK(rawSessionData(contextId), adapter))
    } yield {
      val request = rawSessionData(contextId)
      val pk = genPK(request, adapter)

      val value = rawModel.getOrElse({
        val model = defaultModelRegistry.get[P](attr.className).create(pk)
        lens.set(model)(request.copy(srcId = pk))
      }
      )
      modelAccessFactory.to(value).map(_.to(lens))
    }
  }

  lazy val rawDataAdapter = qAdapterRegistry.byName(classOf[RawSessionData].getName)
  lazy val rawUserAdapter = qAdapterRegistry.byName(classOf[RawUserData].getName)
  lazy val rawRoleAdapter = qAdapterRegistry.byName(classOf[RawRoleData].getName)

  lazy val dataByPK = ByPK(classOf[RawSessionData])
  lazy val userByPK = ByPK(classOf[RawUserData])
  lazy val roleByPK = ByPK(classOf[RawRoleData])

  def newAsk[P <: Product](attr: SessionAttr[P]): Dep[Option[Access[P]]] = {
    val dataNode = Option(
      RawDataNode(
        domainSrcId = attr.pk,
        fieldId = attr.id,
        valueTypeId = 0,
        value = ByteString.EMPTY
      )
    )

    def rawSessionData: ContextId ⇒ RawSessionData = contextId ⇒
      RawSessionData(
        srcId = "",
        sessionKey = contextId,
        dataNode = dataNode
      )

    def rawUserData: UserId ⇒ RawUserData = userId ⇒
      RawUserData(
        srcId = "",
        userId = userId,
        dataNode = dataNode
      )

    def rawRoleData: RoleId ⇒ RawRoleData = userId ⇒
      RawRoleData(
        srcId = "",
        roleId = userId,
        dataNode = dataNode
      )

    import commonRequestFactory._
    for {
      contextId ← askContextId
      rawSession ← rawDataAsk.option(genPK(rawSessionData(contextId), rawDataAdapter))
      userId ← askUserId
      rawUser ← rawUserDataAsk.option(genPK(rawUserData(userId), rawUserAdapter))
      roleId ← askRoleId
      rawRole ← rawRoleDataAsk.option(genPK(rawRoleData(roleId), rawRoleAdapter))
    } yield {
      val rawDataPK = genPK(rawSessionData(contextId), rawDataAdapter)
      val rawUserDataPK = genPK(rawUserData(userId), rawUserAdapter)
      val rawRoleDataPK = genPK(rawRoleData(roleId), rawRoleAdapter)

      val lensRaw = ProdLens[RawSessionData, P](attr.metaList)(
        rawSessionData ⇒ qAdapterRegistry.byId(rawSessionData.dataNode.get.valueTypeId).decode(rawSessionData.dataNode.get.value).asInstanceOf[P],
        value ⇒ rawRoleData ⇒ {
          val valueAdapter = qAdapterRegistry.byName(attr.className)
          val byteString = ToByteString(valueAdapter.encode(value))
          val newDataNode = rawRoleData.dataNode.get.copy(valueTypeId = valueAdapter.id, value = byteString)
          rawRoleData.copy(dataNode = Option(newDataNode))
        }
      )

      val lensRawUser = ProdLens[RawUserData, P](attr.metaList)(
        rawRoleData ⇒ qAdapterRegistry.byId(rawRoleData.dataNode.get.valueTypeId).decode(rawRoleData.dataNode.get.value).asInstanceOf[P],
        value ⇒ rawRoleData ⇒ {
          val valueAdapter = qAdapterRegistry.byName(attr.className)
          val byteString = ToByteString(valueAdapter.encode(value))
          val newDataNode = rawRoleData.dataNode.get.copy(valueTypeId = valueAdapter.id, value = byteString)
          rawRoleData.copy(dataNode = Option(newDataNode))
        }
      )

      val defaultModel: SrcId ⇒ P = defaultModelRegistry.get[P](attr.className).create
      val defaultRawData = lensRaw.set(defaultModel(rawDataPK))(rawSessionData(contextId).copy(srcId = rawDataPK))
      val defaultRawUserData = lensRawUser.set(defaultModel(rawUserDataPK))(rawUserData(userId).copy(srcId = rawUserDataPK))

      val data = DeepRawSessionData[P](rawSession, rawUser, rawRole, (defaultRawData, defaultRawUserData), (rawDataPK, rawUserDataPK, rawRoleDataPK))

      val lens = ProdLens[DeepRawSessionData[P], P](attr.metaList)(
        _.of(qAdapterRegistry),
        value ⇒ deepData ⇒ deepData.set(qAdapterRegistry)(value)(deepData)
      )

      val access: AccessImpl[DeepRawSessionData[P]] = AccessImpl(data, Option(TxDeepRawDataLens(data)), NameMetaAttr("DeepRawSessionData") :: Nil)
      Option(access.to(lens))
    }
  }
}

case object CurrentTimeAskFactoryImpl extends CurrentTimeAskFactoryApi {
  def askCurrentTime(eachNSeconds: Long): Dep[Long] = new RequestDep[Long](CurrentTimeRequest(eachNSeconds))
}
