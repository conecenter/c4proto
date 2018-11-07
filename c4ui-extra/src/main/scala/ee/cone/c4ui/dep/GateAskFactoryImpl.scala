package ee.cone.c4ui.dep

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.ContextTypes.{ContextId, RoleId, UserId}
import ee.cone.c4actor.dep.request.CurrentTimeRequestProtocol.CurrentTimeRequest
import ee.cone.c4actor.dep.{AskByPK, CommonRequestUtilityFactory, Dep, DepFactory}
import ee.cone.c4actor.dep_impl.RequestDep
import ee.cone.c4gate.SessionDataProtocol.{RawDataNode, RawSessionData}
import ee.cone.c4gate.deep_session.DeepSessionDataProtocol.{RawRoleData, RawUserData}
import ee.cone.c4gate.deep_session.{DeepRawSessionData, TxDeepRawDataLens, UserLevelAttr}
import ee.cone.c4gate.{OrigKeyGenerator, SessionAttr}
import ee.cone.c4proto.ToByteString
import okio.ByteString

case class SessionAttrAskFactoryImpl(
  qAdapterRegistry: QAdapterRegistry,
  defaultModelRegistry: DefaultModelRegistry,
  modelAccessFactory: ModelAccessFactory,
  commonRequestFactory: CommonRequestUtilityFactory,
  rawDataAsk: AskByPK[RawSessionData],
  rawUserDataAsk: AskByPK[RawUserData],
  rawRoleDataAsk: AskByPK[RawRoleData],
  idGenUtil: IdGenUtil,
  depFactory: DepFactory
) extends SessionAttrAskFactoryApi with OrigKeyGenerator {

  def askSessionAttrWithPK[P <: Product](attr: SessionAttr[P]): String ⇒ Dep[Option[Access[P]]] = pk ⇒ askSessionAttr(attr.withPK(pk))

  def askSessionAttr[P <: Product](attr: SessionAttr[P]): Dep[Option[Access[P]]] =
    if (attr.metaList.contains(UserLevelAttr))
      for {
        mockRoleOpt ← commonRequestFactory.askMockRole
        result ← {
          mockRoleOpt match {
            case Some((mockRoleId, editable)) ⇒
              if (editable)
                roleAsk(attr, mockRoleId)
              else
                deepAsk(attr, Some(""), Some(mockRoleId))
            case None ⇒
              deepAsk(attr)
          }
        }
      } yield {
        result
      }
    else
      sessionAsk(attr)

  def sessionAsk[P <: Product](attr: SessionAttr[P]): Dep[Option[Access[P]]] = {

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
      rawModel ← rawDataAsk.option(genPK(rawSessionData(contextId), rawDataAdapter))
    } yield {
      val request = rawSessionData(contextId)
      val pk = genPK(request, rawDataAdapter)

      val value: RawSessionData = rawModel.getOrElse({
        val model: P = defaultModelRegistry.get[P](attr.className).create(pk)
        lens.set(model)(request.copy(srcId = pk))
      }
      )
      modelAccessFactory.to(value).map(_.to(lens))
    }
  }

  def roleAsk[P <: Product](attr: SessionAttr[P], roleKey: RoleId): Dep[Option[Access[P]]] = {
    val dataNode = Option(
      RawDataNode(
        domainSrcId = attr.pk,
        fieldId = attr.id,
        valueTypeId = 0,
        value = ByteString.EMPTY
      )
    )

    val lens = ProdLens[RawRoleData, P](attr.metaList)(
      rawRoleData ⇒ qAdapterRegistry.byId(rawRoleData.dataNode.get.valueTypeId).decode(rawRoleData.dataNode.get.value).asInstanceOf[P],
      value ⇒ rawRoleData ⇒ {
        val valueAdapter = qAdapterRegistry.byName(attr.className)
        val byteString = ToByteString(valueAdapter.encode(value))
        val newDataNode = rawRoleData.dataNode.get.copy(valueTypeId = valueAdapter.id, value = byteString)
        rawRoleData.copy(dataNode = Option(newDataNode))
      }
    )

    val rawRoleData: RawRoleData =
      RawRoleData(
        srcId = "",
        roleId = roleKey,
        dataNode = dataNode
      )

    for {
      rawModel ← rawRoleDataAsk.option(genPK(rawRoleData, rawRoleAdapter))
    } yield {
      val pk = genPK(rawRoleData, rawRoleAdapter)

      val value = rawModel.getOrElse({
        val model: P = defaultModelRegistry.get[P](attr.className).create(pk)
        lens.set(model)(rawRoleData.copy(srcId = pk))
      }
      )
      modelAccessFactory.to(value).map(_.to(lens))
    }
  }

  lazy val rawDataAdapter = qAdapterRegistry.byName(classOf[RawSessionData].getName)
  lazy val rawUserAdapter = qAdapterRegistry.byName(classOf[RawUserData].getName)
  lazy val rawRoleAdapter = qAdapterRegistry.byName(classOf[RawRoleData].getName)

  def deepAsk[P <: Product](attr: SessionAttr[P], userIdOpt: Option[UserId] = None, roleIdOpt: Option[RoleId] = None): Dep[Option[Access[P]]] = {
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
      userId ← userIdOpt.map(depFactory.resolvedRequestDep).getOrElse(askUserId)
      rawUser ← rawUserDataAsk.option(genPK(rawUserData(userId), rawUserAdapter))
      roleId ← roleIdOpt.map(depFactory.resolvedRequestDep).getOrElse(askRoleId)
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
