package ee.cone.c4ui.dep

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.ContextTypes.{ContextId, RoleId, UserId}
import ee.cone.c4actor.dep._
import ee.cone.c4actor.time.{CurrentTime, T_Time, TimeGetters}
import ee.cone.c4di.{c4, provide}
import ee.cone.c4gate.SessionDataProtocol.{N_RawDataNode, U_RawSessionData}
import ee.cone.c4gate.deep_session.DeepSessionDataProtocol.{U_RawRoleData, U_RawUserData}
import ee.cone.c4gate.deep_session.{CurrentUserIdKey, DeepRawSessionData, DeepSessionAttrAccessFactoryUtils, RoleLevelAttr, TxDeepRawDataLensFactory, UserLevelAttr}
import ee.cone.c4gate.{KeyGenerator, SessionAttr}
import ee.cone.c4proto._
import okio.ByteString

@c4("SessionAttrAskCompApp") final case class SessionAttrAskFactoryImpl(
  qAdapterRegistry: QAdapterRegistry,
  origMetaRegistry: OrigMetaRegistry,
  modelFactory: ModelFactory,
  modelAccessFactory: RModelAccessFactory,
  commonRequestFactory: CommonRequestUtilityFactory,
  askByPKFactory: AskByPKFactory,
  idGenUtil: IdGenUtil,
  depFactory: DepFactory,
  txDeepRawDataLensFactory: TxDeepRawDataLensFactory,
  getU_RawSessionData: GetByPK[U_RawSessionData],
  getU_RawRoleData: GetByPK[U_RawRoleData],
) extends SessionAttrAskFactory with KeyGenerator with DeepSessionAttrAccessFactoryUtils {
  private lazy val rawDataAsk: AskByPK[U_RawSessionData] = askByPKFactory.forClass(classOf[U_RawSessionData])
  private lazy val rawUserDataAsk: AskByPK[U_RawUserData] = askByPKFactory.forClass(classOf[U_RawUserData])
  private lazy val rawRoleDataAsk: AskByPK[U_RawRoleData] = askByPKFactory.forClass(classOf[U_RawRoleData])

  @provide def askByPKs: Seq[AbstractAskByPK] = rawDataAsk :: rawUserDataAsk :: rawRoleDataAsk :: Nil

  def askSessionAttrWithPK[P <: Product](attr: SessionAttr[P]): String => Dep[Option[Access[P]]] = pk => askSessionAttr(attr.withPK(pk))

  def askSessionAttr[P <: Product](attr: SessionAttr[P]): Dep[Option[Access[P]]] =
    askSessionAttrWithDefault(attr, srcId => modelFactory.create[P](attr.className)(srcId))

  def askSessionAttrWithDefault[P <: Product](attr: SessionAttr[P], default: SrcId => P): Dep[Option[Access[P]]] =
    if (attr.metaList.contains(UserLevelAttr))
      for {
        mockRoleOpt <- commonRequestFactory.askMockRole
        result <- {
          mockRoleOpt match {
            case Some((mockRoleId, editable)) =>
              if (editable)
                roleAsk(attr, mockRoleId, default)
              else
                deepAsk(attr, default, Some(""), Some(mockRoleId))
            case None =>
              deepAsk(attr, default)
          }
        }
      } yield {
        result
      }
    else
      sessionAsk(attr, default)

  def sessionAsk[P <: Product](attr: SessionAttr[P], default: SrcId => P): Dep[Option[Access[P]]] = {

    val lens = sessionLens(attr)(
      rawData => qAdapterRegistry.byId(rawData.dataNode.get.valueTypeId).decode(rawData.dataNode.get.value).asInstanceOf[P],
      value => rawData => {
        val valueAdapter = qAdapterRegistry.byName(attr.className)
        val byteString = ToByteString(valueAdapter.encode(value))
        val newDataNode = rawData.dataNode.get.copy(valueTypeId = valueAdapter.id, value = byteString)
        rawData.copy(dataNode = Option(newDataNode))
      }
    )

    def rawSessionData: ContextId => U_RawSessionData = contextId =>
      U_RawSessionData(
        srcId = "",
        sessionKey = contextId,
        dataNode = Option(
          N_RawDataNode(
            domainSrcId = attr.pk,
            fieldId = attr.id,
            valueTypeId = 0,
            value = ByteString.EMPTY
          )
        )
      )

    import commonRequestFactory._
    for {
      contextId <- askContextId
      rawModel <- rawDataAsk.option(genPK(rawSessionData(contextId), rawDataAdapter))
    } yield {
      val request = rawSessionData(contextId)
      val pk = genPK(request, rawDataAdapter)

      val value: U_RawSessionData = rawModel.getOrElse({
        val model: P = default(pk)
        lens.set(model)(request.copy(srcId = pk))
      }
      )
      modelAccessFactory.to(getU_RawSessionData, value).map(_.to(lens))
    }
  }

  def roleAsk[P <: Product](attr: SessionAttr[P], roleKey: RoleId, default: SrcId => P): Dep[Option[Access[P]]] = {
    val dataNode = Option(
      N_RawDataNode(
        domainSrcId = attr.pk,
        fieldId = attr.id,
        valueTypeId = 0,
        value = ByteString.EMPTY
      )
    )

    val lens = roleLens(attr)(
      rawRoleData => qAdapterRegistry.byId(rawRoleData.dataNode.get.valueTypeId).decode(rawRoleData.dataNode.get.value).asInstanceOf[P],
      value => rawRoleData => {
        val valueAdapter = qAdapterRegistry.byName(attr.className)
        val byteString = ToByteString(valueAdapter.encode(value))
        val newDataNode = rawRoleData.dataNode.get.copy(valueTypeId = valueAdapter.id, value = byteString)
        rawRoleData.copy(dataNode = Option(newDataNode))
      }
    )

    val rawRoleData: U_RawRoleData =
      U_RawRoleData(
        srcId = "",
        roleId = roleKey,
        dataNode = dataNode
      )

    for {
      rawModel <- rawRoleDataAsk.option(genPK(rawRoleData, rawRoleAdapter))
    } yield {
      val pk = genPK(rawRoleData, rawRoleAdapter)

      val value: U_RawRoleData = rawModel.getOrElse({
        val model: P = default(pk)
        lens.set(model)(rawRoleData.copy(srcId = pk))
      }
      )
      modelAccessFactory.to(getU_RawRoleData, value).map(_.to(lens))
    }
  }

  lazy val rawDataAdapter: ProtoAdapter[Product] with HasId = qAdapterRegistry.byName(classOf[U_RawSessionData].getName)
  lazy val rawUserAdapter: ProtoAdapter[Product] with HasId = qAdapterRegistry.byName(classOf[U_RawUserData].getName)
  lazy val rawRoleAdapter: ProtoAdapter[Product] with HasId = qAdapterRegistry.byName(classOf[U_RawRoleData].getName)

  def deepAsk[P <: Product](attr: SessionAttr[P], default: SrcId => P, userIdOpt: Option[UserId] = None, roleIdOpt: Option[RoleId] = None): Dep[Option[Access[P]]] = {
    val dataNode = Option(
      N_RawDataNode(
        domainSrcId = attr.pk,
        fieldId = attr.id,
        valueTypeId = 0,
        value = ByteString.EMPTY
      )
    )

    def rawSessionData: ContextId => U_RawSessionData = contextId =>
      U_RawSessionData(
        srcId = "",
        sessionKey = contextId,
        dataNode = dataNode
      )

    def rawUserData: UserId => U_RawUserData = userId =>
      U_RawUserData(
        srcId = "",
        userId = userId,
        dataNode = dataNode
      )

    def rawRoleData: RoleId => U_RawRoleData = userId =>
      U_RawRoleData(
        srcId = "",
        roleId = userId,
        dataNode = dataNode
      )

    import commonRequestFactory._
    def userIdAsk: Dep[UserId] = if (attr.metaList.contains(RoleLevelAttr)) depFactory.resolvedRequestDep("") else askUserId

    for {
      contextId <- askContextId
      rawSession <- rawDataAsk.option(genPK(rawSessionData(contextId), rawDataAdapter))
      userId <- userIdOpt.map(depFactory.resolvedRequestDep).getOrElse(userIdAsk)
      rawUser <- rawUserDataAsk.option(genPK(rawUserData(userId), rawUserAdapter))
      roleId <- roleIdOpt.map(depFactory.resolvedRequestDep).getOrElse(askRoleId)
      rawRole <- rawRoleDataAsk.option(genPK(rawRoleData(roleId), rawRoleAdapter))
    } yield {
      val rawDataPK = genPK(rawSessionData(contextId), rawDataAdapter)
      val rawUserDataPK = genPK(rawUserData(userId), rawUserAdapter)
      val rawRoleDataPK = genPK(rawRoleData(roleId), rawRoleAdapter)

      val lensRaw = sessionLens(attr)(
        rawSessionData => qAdapterRegistry.byId(rawSessionData.dataNode.get.valueTypeId).decode(rawSessionData.dataNode.get.value).asInstanceOf[P],
        value => rawRoleData => {
          val valueAdapter = qAdapterRegistry.byName(attr.className)
          val byteString = ToByteString(valueAdapter.encode(value))
          val newDataNode = rawRoleData.dataNode.get.copy(valueTypeId = valueAdapter.id, value = byteString)
          rawRoleData.copy(dataNode = Option(newDataNode))
        }
      )

      val lensRawUser = userLens(attr)(
        rawRoleData => qAdapterRegistry.byId(rawRoleData.dataNode.get.valueTypeId).decode(rawRoleData.dataNode.get.value).asInstanceOf[P],
        value => rawRoleData => {
          val valueAdapter = qAdapterRegistry.byName(attr.className)
          val byteString = ToByteString(valueAdapter.encode(value))
          val newDataNode = rawRoleData.dataNode.get.copy(valueTypeId = valueAdapter.id, value = byteString)
          rawRoleData.copy(dataNode = Option(newDataNode))
        }
      )

      val defaultRawData = lensRaw.set(default(rawDataPK))(rawSessionData(contextId).copy(srcId = rawDataPK))
      val defaultRawUserData = lensRawUser.set(default(rawUserDataPK))(rawUserData(userId).copy(srcId = rawUserDataPK))

      val data = DeepRawSessionData[P](rawSession, rawUser, rawRole, (defaultRawData, defaultRawUserData), (rawDataPK, rawUserDataPK, rawRoleDataPK))
      val lens = deepLens(attr)
      val access: AccessImpl[DeepRawSessionData[P]] = AccessImpl(data, Option(txDeepRawDataLensFactory.create(data)), NameMetaAttr("DeepRawSessionData") :: Nil)
      Option(access.to(lens))
    }
  }
}

class CurrentTimeRequestFactoryImpl(val byPkAsk: AskByPK[_ <: T_Time], time: CurrentTime) extends CurrentTimeRequestFactory {
  def ask: Dep[Long] = byPkAsk.option(time.srcId).map(_.map(_.millis).getOrElse(0L))
}

@c4("CurrentTimeAskCompApp") final class CurrentTimeRequestFactoryProvider(
  requestFactories: List[CurrentTimeRequestFactory]
) {
  @provide def askByPks: Seq[AbstractAskByPK] =
    requestFactories.map(_.byPkAsk)
}

@c4("CurrentTimeAskCompApp") final case class CurrentTimeAskFactoryImpl(
  askByPKFactory: AskByPKFactory,
  timeGetters: TimeGetters
) extends CurrentTimeAskFactory {
  def askCurrentTime(time: CurrentTime): CurrentTimeRequestFactory =
    new CurrentTimeRequestFactoryImpl(askByPKFactory.forClass(timeGetters(time).cl), time)
}
