package ee.cone.c4gate.deep_session

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4gate.{CurrentSessionKey, OrigKeyGenerator, SessionAttr, SessionAttrAccessFactory}
import ee.cone.c4gate.SessionDataProtocol.{RawDataNode, RawSessionData}
import ee.cone.c4gate.deep_session.DeepSessionDataProtocol.{RawRoleData, RawUserData}
import ee.cone.c4proto.ToByteString
import okio.ByteString

class DeepSessionAttrAccessFactoryImpl(
  registry: QAdapterRegistry,
  defaultModelRegistry: DefaultModelRegistry,
  modelAccessFactory: ModelAccessFactory,
  val idGenUtil: IdGenUtil,
  sessionAttrAccessFactory: SessionAttrAccessFactory
) extends DeepSessionAttrAccessFactory with OrigKeyGenerator {
  lazy val rawDataAdapter = registry.byName(classOf[RawSessionData].getName)
  lazy val rawUserAdapter = registry.byName(classOf[RawUserData].getName)
  lazy val rawRoleAdapter = registry.byName(classOf[RawRoleData].getName)

  lazy val dataByPK = ByPK(classOf[RawSessionData])
  lazy val userByPK = ByPK(classOf[RawUserData])
  lazy val roleByPK = ByPK(classOf[RawRoleData])


  def to[P <: Product](attr: SessionAttr[P]): Context ⇒ Option[Access[P]] =
    if (attr.metaList.collectFirst { case UserLevelAttr ⇒ "" }.isEmpty) {
      sessionAttrAccessFactory.to(attr)
    } else {
      toUser(attr)
    }

  def toUser[P <: Product](attr: SessionAttr[P]): Context ⇒ Option[Access[P]] = local ⇒ {
    val dataNode = RawDataNode(
      domainSrcId = attr.pk,
      fieldId = attr.id,
      valueTypeId = 0,
      value = ByteString.EMPTY
    )
    // Session
    val contextKey = CurrentSessionKey.of(local)
    val stubRawData: RawSessionData = RawSessionData(
      srcId = "",
      sessionKey = contextKey,
      dataNode = Option(dataNode)
    )
    val rawDataPK = genPK(stubRawData, rawDataAdapter)
    val rawDataOpt: Option[RawSessionData] = dataByPK.of(local).get(rawDataPK)
    // User
    val userKey = CurrentUserIdKey.of(local)
    val stubRawUserData: RawUserData = RawUserData(
      srcId = "",
      userId = userKey,
      dataNode = Option(dataNode)
    )
    val rawUserDataPK = genPK(stubRawUserData, rawUserAdapter)
    val rawUserDataOpt: Option[RawUserData] = userByPK.of(local).get(rawUserDataPK)
    // Role
    val roleKey = CurrentRoleIdKey.of(local)
    val stubRawRoleData: RawRoleData = RawRoleData(
      srcId = "",
      roleId = roleKey,
      dataNode = Option(dataNode)
    )
    val rawRoleDataPK = genPK(stubRawRoleData, rawRoleAdapter)
    val rawRoleDataOpt: Option[RawRoleData] = roleByPK.of(local).get(rawRoleDataPK)
    // Rest
    val lensRaw = ProdLens[RawSessionData, P](attr.metaList)(
      rawSessionData ⇒ registry.byId(rawSessionData.dataNode.get.valueTypeId).decode(rawSessionData.dataNode.get.value).asInstanceOf[P],
      value ⇒ rawRoleData ⇒ {
        val valueAdapter = registry.byName(attr.className)
        val byteString = ToByteString(valueAdapter.encode(value))
        val newDataNode = rawRoleData.dataNode.get.copy(valueTypeId = valueAdapter.id, value = byteString)
        rawRoleData.copy(dataNode = Option(newDataNode))
      }
    )

    val lensRawUser = ProdLens[RawUserData, P](attr.metaList)(
      rawRoleData ⇒ registry.byId(rawRoleData.dataNode.get.valueTypeId).decode(rawRoleData.dataNode.get.value).asInstanceOf[P],
      value ⇒ rawRoleData ⇒ {
        val valueAdapter = registry.byName(attr.className)
        val byteString = ToByteString(valueAdapter.encode(value))
        val newDataNode = rawRoleData.dataNode.get.copy(valueTypeId = valueAdapter.id, value = byteString)
        rawRoleData.copy(dataNode = Option(newDataNode))
      }
    )

    val defaultModel: SrcId ⇒ P = defaultModelRegistry.get[P](attr.className).create
    val defaultRawData = lensRaw.set(defaultModel(rawDataPK))(stubRawData.copy(srcId = rawDataPK))
    val defaultRawUserData = lensRawUser.set(defaultModel(rawUserDataPK))(stubRawUserData.copy(srcId = rawUserDataPK))

    val data = DeepRawSessionData[P](rawDataOpt, rawUserDataOpt, rawRoleDataOpt, (defaultRawData, defaultRawUserData), (rawDataPK, rawUserDataPK, rawRoleDataPK))

    val lens = ProdLens[DeepRawSessionData[P], P](attr.metaList)(
      _.of(registry),
      value ⇒ deepData ⇒ deepData.set(registry)(value)(deepData)
    )

    val access: AccessImpl[DeepRawSessionData[P]] = AccessImpl(data, Option(TxDeepRawDataLens(data)), NameMetaAttr("DeepRawSessionData") :: Nil)
    Option(access.to(lens))
  }

  def toRole[P <: Product](attr: SessionAttr[P]): Context ⇒ Option[Access[P]] = {
    val dataNode = RawDataNode(
      domainSrcId = attr.pk,
      fieldId = attr.id,
      valueTypeId = 0,
      value = ByteString.EMPTY
    )

    val lens = ProdLens[RawRoleData, P](attr.metaList)(
      rawRoleData ⇒ registry.byId(rawRoleData.dataNode.get.valueTypeId).decode(rawRoleData.dataNode.get.value).asInstanceOf[P],
      value ⇒ rawRoleData ⇒ {
        val valueAdapter = registry.byName(attr.className)
        val byteString = ToByteString(valueAdapter.encode(value))
        val newDataNode = rawRoleData.dataNode.get.copy(valueTypeId = valueAdapter.id, value = byteString)
        rawRoleData.copy(dataNode = Option(newDataNode))
      }
    )

    local ⇒ {
      val roleKey = CurrentRoleIdKey.of(local)
      val stubRawRoleData = RawRoleData(
        srcId = "",
        roleId = roleKey,
        dataNode = Option(dataNode)
      )
      val pk = genPK(stubRawRoleData, rawRoleAdapter)
      val value = roleByPK.of(local).getOrElse(pk, {
        val model = defaultModelRegistry.get[P](attr.className).create(pk)
        lens.set(model)(stubRawRoleData.copy(srcId = pk))
      }
      )
      modelAccessFactory.to(value).map(_.to(lens))
    }
  }
}

case class DeepRawSessionData[P <: Product](
  sessionData: Option[RawSessionData],
  userData: Option[RawUserData],
  roleData: Option[RawRoleData],
  default: (RawSessionData, RawUserData),
  srcIds: (SrcId, SrcId, SrcId)
) {


  override def toString: SrcId = s"$productPrefix(\n\t$sessionData\n\t$userData\n\t$roleData\n\t$default\n\t$srcIds\n)"

  def get: (Long, okio.ByteString) = {
    if (sessionData.isDefined)
      sessionData.get.dataNode.get match {
        case p ⇒ (p.valueTypeId, p.value)
      }
    else if (userData.isDefined)
      userData.get.dataNode.get match {
        case p ⇒ (p.valueTypeId, p.value)
      }
    else if (roleData.isDefined)
      roleData.get.dataNode.get match {
        case p ⇒ (p.valueTypeId, p.value)
      }
    else
      default match {
        case (p, _) ⇒ (p.dataNode.get.valueTypeId, p.dataNode.get.value)
      }
  }

  def of: QAdapterRegistry ⇒ P = registry ⇒ {
    val (id, value) = get
    registry.byId(id).decode(value).asInstanceOf[P]
  }

  def set: QAdapterRegistry ⇒ P ⇒ DeepRawSessionData[P] ⇒ DeepRawSessionData[P] = registry ⇒ model ⇒ old ⇒ {
    val adapter = registry.byName(model.getClass.getName)
    val byteString = ToByteString(adapter.encode(model))
    val (defaultRaw, defaultUser) = old.default
    val newDataNode = defaultRaw.dataNode.get.copy(valueTypeId = adapter.id, value = byteString)
    old.copy(sessionData = Option(defaultRaw.copy(dataNode = Option(newDataNode))), userData = Option(defaultUser.copy(dataNode = Option(newDataNode))))
  }
}

case class TxDeepRawDataLens[P <: Product](initialValue: DeepRawSessionData[P]) extends AbstractLens[Context, DeepRawSessionData[P]] {
  lazy val dataByPK = ByPK(classOf[RawSessionData])
  lazy val userByPK = ByPK(classOf[RawUserData])
  lazy val roleByPK = ByPK(classOf[RawRoleData])

  def of: Context ⇒ DeepRawSessionData[P] = local ⇒ {
    val (rawId, userId, roleId) = initialValue.srcIds
    val rawOpt = dataByPK.of(local).get(rawId)
    val userOpt = userByPK.of(local).get(userId)
    val roleOpt = roleByPK.of(local).get(roleId)
    initialValue.copy(sessionData = rawOpt, userData = userOpt, roleData = roleOpt)
  }

  def set: DeepRawSessionData[P] ⇒ Context ⇒ Context = value ⇒ local ⇒ {
    if (initialValue != of(local)) throw new Exception(s"'$initialValue' != '${of(local)}'")
    val DeepRawSessionData(raw, user, _, _, _) = value
    val rawEvent = raw.map(LEvent.update).toList.flatten
    val userEvent = user.map(LEvent.update).toList.flatten
    TxAdd(rawEvent ++ userEvent)(local)
  }

}


