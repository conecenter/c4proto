package ee.cone.c4gate.deep_session

import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{MortalFactory, WithPK}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by}
import ee.cone.c4gate.deep_session.DeepSessionDataProtocol.{RawRoleData, RawUserData}

object DeepSessionDataAssembles {
  def apply(mortal: MortalFactory, userModel: Class[_ <: Product], roleModel: Class[_ <: Product]): List[Assemble] =
    mortal(classOf[RawUserData]) ::
      mortal(classOf[RawRoleData]) ::
      new RawUserDataAssemble(userModel) ::
      new RawRoleDataAssemble(roleModel) :: Nil
}

@assemble class RawUserDataAssemble[User <: Product](modelCl: Class[User]) extends Assemble {
  type UserId = SrcId

  def RawUserDataByRoleId(
    rawData: SrcId,
    rawUserData: Values[RawUserData]
  ): Values[(UserId, RawUserData)] =
    for {
      rawData ← rawUserData
    } yield rawData.userId → rawData

  def ModelWithRawUserDataLife(
    modelId: SrcId,
    @by[UserId] rawUserData: Values[RawUserData],
    models: Values[User]
  ): Values[(Alive, RawUserData)] =
    for {
      rawData ← rawUserData
      _ ← models
    } yield WithPK(rawData)
}

@assemble class RawRoleDataAssemble[Role <: Product](roleCl: Class[Role]) extends Assemble {
  type RoleId = SrcId

  def RawRoleDataByRoleId(
    rawData: SrcId,
    rawRoleData: Values[RawRoleData]
  ): Values[(RoleId, RawRoleData)] =
    for {
      rawData ← rawRoleData
    } yield rawData.roleId → rawData

  def ModelWithRawRoleDataLife(
    modelId: SrcId,
    @by[RoleId] rawRoleData: Values[RawRoleData],
    models: Values[Role]
  ): Values[(Alive, RawRoleData)] =
    for {
      rawData ← rawRoleData
      _ ← models
    } yield WithPK(rawData)
}