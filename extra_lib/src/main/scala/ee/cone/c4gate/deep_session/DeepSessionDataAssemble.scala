package ee.cone.c4gate.deep_session

import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{AssembleName, MortalFactory, WithPK}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble, by}
import ee.cone.c4gate.deep_session.DeepSessionDataProtocol.{U_RawRoleData, U_RawUserData}

object DeepSessionDataAssembles {
  def apply(mortal: MortalFactory, userModel: Class[_ <: Product], roleModel: Class[_ <: Product]): List[Assemble] =
    mortal(classOf[U_RawUserData]) ::
      mortal(classOf[U_RawRoleData]) ::
      new RawUserDataAssemble(userModel) ::
      new RawRoleDataAssemble(roleModel) :: Nil
}

@assemble class RawUserDataAssembleBase[User <: Product](modelCl: Class[User])
  extends AssembleName("RawUserDataAssemble", modelCl) {
  type UserId = SrcId

  def RawUserDataByRoleId(
    srcId: SrcId,
    rawData: Each[U_RawUserData]
  ): Values[(UserId, U_RawUserData)] =
    List(rawData.userId -> rawData)

  def ModelWithRawUserDataLife(
    modelId: SrcId,
    @by[UserId] rawData: Each[U_RawUserData],
    model: Each[User]
  ): Values[(Alive, U_RawUserData)] = List(WithPK(rawData))
}

@assemble class RawRoleDataAssembleBase[Role <: Product](roleCl: Class[Role])
  extends AssembleName("RawRoleDataAssemble", roleCl) {
  type RoleId = SrcId

  def RawRoleDataByRoleId(
    srcId: SrcId,
    rawData: Each[U_RawRoleData]
  ): Values[(RoleId, U_RawRoleData)] = List(rawData.roleId -> rawData)

  def ModelWithRawRoleDataLife(
    modelId: SrcId,
    @by[RoleId] rawData: Each[U_RawRoleData],
    model: Each[Role]
  ): Values[(Alive, U_RawRoleData)] = List(WithPK(rawData))
}