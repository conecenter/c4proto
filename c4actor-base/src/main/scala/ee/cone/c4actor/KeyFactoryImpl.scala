package ee.cone.c4actor

import ee.cone.c4actor.Types.{ClName, SrcId}
import ee.cone.c4assemble.{AssembledKey, IndexUtil}
import ee.cone.c4di.c4

@c4("RichDataCompApp") class SwitchOrigKeyFactoryHolder(proposition: Option[OrigKeyFactoryProposition], byPKKeyFactory: KeyFactory)
  extends OrigKeyFactoryFinalHolder(proposition.fold(byPKKeyFactory)(_.value))

@c4("RichDataCompApp") case class DefaultKeyFactory(composes: IndexUtil)(
  srcIdAlias: String = "SrcId",
  srcIdClass: ClName = classOf[SrcId].getName
) extends KeyFactory {
  def rawKey(className: String): AssembledKey =
    composes.joinKey(was = false, srcIdAlias, srcIdClass, className)
}
