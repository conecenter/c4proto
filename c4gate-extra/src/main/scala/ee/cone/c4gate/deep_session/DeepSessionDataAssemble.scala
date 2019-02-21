package ee.cone.c4gate.deep_session

import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{MortalFactory, WithPK}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble, by}
import ee.cone.c4gate.deep_session.DeepSessionDataProtocol.{RawRoleData, RawUserData}

object DeepSessionDataAssembles {
  def apply(mortal: MortalFactory, userModel: Class[_ <: Product], roleModel: Class[_ <: Product]): List[Assemble] =
    mortal(classOf[RawUserData]) ::
      mortal(classOf[RawRoleData]) ::
      new RawUserDataAssemble(userModel) ::
      new RawRoleDataAssemble(roleModel) :: Nil
}

@assemble class RawUserDataAssemble[User <: Product](modelCl: Class[User])   {
  type UserId = SrcId

  def RawUserDataByRoleId(
    srcId: SrcId,
    rawData: Each[RawUserData]
  ): Values[(UserId, RawUserData)] =
    List(rawData.userId → rawData)

  def ModelWithRawUserDataLife(
    modelId: SrcId,
    @by[UserId] rawData: Each[RawUserData],
    model: Each[User]
  ): Values[(Alive, RawUserData)] = List(WithPK(rawData))
}

@assemble class RawRoleDataAssemble[Role <: Product](roleCl: Class[Role])   {
  type RoleId = SrcId

  def RawRoleDataByRoleId(
    srcId: SrcId,
    rawData: Each[RawRoleData]
  ): Values[(RoleId, RawRoleData)] = List(rawData.roleId → rawData)

  def ModelWithRawRoleDataLife(
    modelId: SrcId,
    @by[RoleId] rawData: Each[RawRoleData],
    model: Each[Role]
  ): Values[(Alive, RawRoleData)] = List(WithPK(rawData))
}