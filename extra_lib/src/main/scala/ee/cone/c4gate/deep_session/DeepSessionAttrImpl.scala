package ee.cone.c4gate.deep_session

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4gate.{CurrentSessionKey, KeyGenerator, SessionAttr, SessionAttrAccessFactory}
import ee.cone.c4gate.SessionDataProtocol.{N_RawDataNode, U_RawSessionData}
import ee.cone.c4gate.deep_session.DeepSessionDataProtocol.{U_RawRoleData, U_RawUserData}
import ee.cone.c4proto.ToByteString
import okio.ByteString

@c4("DeepSessionAttrFactoryImplApp") final class ExcludeSessionAttrAccessFactory extends Exclude[SessionAttrAccessFactory]
@c4("DeepSessionAttrFactoryImplApp") final class DeepSessionAttrAccessFactoryImpl(
  registry: QAdapterRegistry,
  modelFactory: ModelFactory,
  modelAccessFactory: RModelAccessFactory,
  val idGenUtil: IdGenUtil,
  sessionAttrAccessFactory: ProbablyExcluded[SessionAttrAccessFactory],
  txDeepRawDataLensFactory: TxDeepRawDataLensFactory,
  roleByPK: GetByPK[U_RawRoleData],
  dataByPK: GetByPK[U_RawSessionData],
  userByPK: GetByPK[U_RawUserData],
) extends DeepSessionAttrAccessFactory with SessionAttrAccessFactory with KeyGenerator {
  lazy val rawDataAdapter = registry.byName(classOf[U_RawSessionData].getName)
  lazy val rawUserAdapter = registry.byName(classOf[U_RawUserData].getName)
  lazy val rawRoleAdapter = registry.byName(classOf[U_RawRoleData].getName)

  def to[P <: Product](attr: SessionAttr[P]): Context => Option[Access[P]] =
    if (attr.metaList.collectFirst { case UserLevelAttr => "" }.isEmpty) {
      sessionAttrAccessFactory.value.to(attr)
    } else {
      toUser(attr)
    }

  def toUser[P <: Product](attr: SessionAttr[P]): Context => Option[Access[P]] = local => {
    val dataNode = N_RawDataNode(
      domainSrcId = attr.pk,
      fieldId = attr.id,
      valueTypeId = 0,
      value = ByteString.EMPTY
    )
    // Session
    val contextKey = CurrentSessionKey.of(local)
    val stubRawData: U_RawSessionData = U_RawSessionData(
      srcId = "",
      sessionKey = contextKey,
      dataNode = Option(dataNode)
    )
    val rawDataPK = genPK(stubRawData, rawDataAdapter)
    val rawDataOpt: Option[U_RawSessionData] = dataByPK.ofA(local).get(rawDataPK)
    // User
    val userKey = CurrentUserIdKey.of(local)
    val stubRawUserData: U_RawUserData = U_RawUserData(
      srcId = "",
      userId = userKey,
      dataNode = Option(dataNode)
    )
    val rawUserDataPK = genPK(stubRawUserData, rawUserAdapter)
    val rawUserDataOpt: Option[U_RawUserData] = userByPK.ofA(local).get(rawUserDataPK)
    // Role
    val roleKey = CurrentRoleIdKey.of(local)
    val stubRawRoleData: U_RawRoleData = U_RawRoleData(
      srcId = "",
      roleId = roleKey,
      dataNode = Option(dataNode)
    )
    val rawRoleDataPK = genPK(stubRawRoleData, rawRoleAdapter)
    val rawRoleDataOpt: Option[U_RawRoleData] = roleByPK.ofA(local).get(rawRoleDataPK)
    // Rest
    val lensRaw = ProdLensNonstrict[U_RawSessionData, P](attr.metaList)(
      rawSessionData => registry.byId(rawSessionData.dataNode.get.valueTypeId).decode(rawSessionData.dataNode.get.value).asInstanceOf[P],
      value => rawRoleData => {
        val valueAdapter = registry.byName(attr.className)
        val byteString = ToByteString(valueAdapter.encode(value))
        val newDataNode = rawRoleData.dataNode.get.copy(valueTypeId = valueAdapter.id, value = byteString)
        rawRoleData.copy(dataNode = Option(newDataNode))
      }
    )

    val lensRawUser = ProdLensNonstrict[U_RawUserData, P](attr.metaList)(
      rawRoleData => registry.byId(rawRoleData.dataNode.get.valueTypeId).decode(rawRoleData.dataNode.get.value).asInstanceOf[P],
      value => rawRoleData => {
        val valueAdapter = registry.byName(attr.className)
        val byteString = ToByteString(valueAdapter.encode(value))
        val newDataNode = rawRoleData.dataNode.get.copy(valueTypeId = valueAdapter.id, value = byteString)
        rawRoleData.copy(dataNode = Option(newDataNode))
      }
    )

    val defaultModel: SrcId => P = srcId => modelFactory.create[P](attr.className)(srcId)
    val defaultRawData = lensRaw.set(defaultModel(rawDataPK))(stubRawData.copy(srcId = rawDataPK))
    val defaultRawUserData = lensRawUser.set(defaultModel(rawUserDataPK))(stubRawUserData.copy(srcId = rawUserDataPK))

    val data = DeepRawSessionData[P](rawDataOpt, rawUserDataOpt, rawRoleDataOpt, (defaultRawData, defaultRawUserData), (rawDataPK, rawUserDataPK, rawRoleDataPK))

    val lens = ProdLensNonstrict[DeepRawSessionData[P], P](attr.metaList)(
      _.of(registry),
      value => deepData => deepData.set(registry)(value)(deepData)
    )

    val access: AccessImpl[DeepRawSessionData[P]] = AccessImpl(data, Option(txDeepRawDataLensFactory.create(data)), NameMetaAttr("DeepRawSessionData") :: Nil)
    Option(access.to(lens))
  }

  def toRole[P <: Product](attr: SessionAttr[P]): Context => Option[Access[P]] = {
    val dataNode = N_RawDataNode(
      domainSrcId = attr.pk,
      fieldId = attr.id,
      valueTypeId = 0,
      value = ByteString.EMPTY
    )

    val lens = ProdLensNonstrict[U_RawRoleData, P](attr.metaList)(
      rawRoleData => registry.byId(rawRoleData.dataNode.get.valueTypeId).decode(rawRoleData.dataNode.get.value).asInstanceOf[P],
      value => rawRoleData => {
        val valueAdapter = registry.byName(attr.className)
        val byteString = ToByteString(valueAdapter.encode(value))
        val newDataNode = rawRoleData.dataNode.get.copy(valueTypeId = valueAdapter.id, value = byteString)
        rawRoleData.copy(dataNode = Option(newDataNode))
      }
    )

    local => {
      val roleKey = CurrentRoleIdKey.of(local)
      val stubRawRoleData = U_RawRoleData(
        srcId = "",
        roleId = roleKey,
        dataNode = Option(dataNode)
      )
      val pk = genPK(stubRawRoleData, rawRoleAdapter)
      val value = roleByPK.ofA(local).getOrElse(pk, {
        val model = modelFactory.create[P](attr.className)(pk)
        lens.set(model)(stubRawRoleData.copy(srcId = pk))
      }
      )
      modelAccessFactory.to(roleByPK,value).map(_.to(lens))
    }
  }
}

case class DeepRawSessionData[P <: Product](
  sessionData: Option[U_RawSessionData],
  userData: Option[U_RawUserData],
  roleData: Option[U_RawRoleData],
  default: (U_RawSessionData, U_RawUserData),
  srcIds: (SrcId, SrcId, SrcId)
) {


  override def toString: SrcId = s"$productPrefix(\n\t$sessionData\n\t$userData\n\t$roleData\n\t$default\n\t$srcIds\n)"

  def get: (Long, okio.ByteString) = {
    if (sessionData.isDefined)
      sessionData.get.dataNode.get match {
        case p => (p.valueTypeId, p.value)
      }
    else if (userData.isDefined)
      userData.get.dataNode.get match {
        case p => (p.valueTypeId, p.value)
      }
    else if (roleData.isDefined)
      roleData.get.dataNode.get match {
        case p => (p.valueTypeId, p.value)
      }
    else
      default match {
        case (p, _) => (p.dataNode.get.valueTypeId, p.dataNode.get.value)
      }
  }

  def of: QAdapterRegistry => P = registry => {
    val (id, value) = get
    registry.byId(id).decode(value).asInstanceOf[P]
  }

  def set: QAdapterRegistry => P => DeepRawSessionData[P] => DeepRawSessionData[P] = registry => model => old => {
    val adapter = registry.byName(model.getClass.getName)
    val byteString = ToByteString(adapter.encode(model))
    val (defaultRaw, defaultUser) = old.default
    val newDataNode = defaultRaw.dataNode.get.copy(valueTypeId = adapter.id, value = byteString)
    old.copy(sessionData = Option(defaultRaw.copy(dataNode = Option(newDataNode))), userData = Option(defaultUser.copy(dataNode = Option(newDataNode))))
  }
}

@c4multi("TxDeepRawDataLensApp") final case class TxDeepRawDataLens[P <: Product](initialValue: DeepRawSessionData[P])(
  dataByPK: GetByPK[U_RawSessionData],
  userByPK: GetByPK[U_RawUserData],
  roleByPK: GetByPK[U_RawRoleData],
  txAdd: LTxAdd,
) extends AbstractLens[Context, DeepRawSessionData[P]] {

  def of: Context => DeepRawSessionData[P] = local => {
    val (rawId, userId, roleId) = initialValue.srcIds
    val rawOpt = dataByPK.ofA(local).get(rawId)
    val userOpt = userByPK.ofA(local).get(userId)
    val roleOpt = roleByPK.ofA(local).get(roleId)
    initialValue.copy(sessionData = rawOpt, userData = userOpt, roleData = roleOpt)
  }

  def set: DeepRawSessionData[P] => Context => Context = value => local => {
    if (initialValue != of(local)) throw new Exception(s"'$initialValue' != '${of(local)}'")
    val DeepRawSessionData(raw, user, _, _, _) = value
    val rawEvent = raw.map(LEvent.update).toList.flatten
    val userEvent = user.map(LEvent.update).toList.flatten
    txAdd.add(rawEvent ++ userEvent)(local)
  }

}


