package ee.cone.c4actor

import ee.cone.c4actor.Types.{ClName, SrcId}
import ee.cone.c4assemble.Types.{Index, Values}
import ee.cone.c4assemble.{AssembledKey, Getter, IndexUtil, Single, Types}
import ee.cone.c4di.Types.ComponentFactory
import ee.cone.c4di.{c4, provide}

@c4("RichDataCompApp") final class SwitchOrigKeyFactoryHolder(proposition: Option[OrigKeyFactoryProposition], byPKKeyFactory: KeyFactory)
  extends OrigKeyFactoryFinalHolder(proposition.fold(byPKKeyFactory)(_.value))

@c4("RichDataCompApp") final case class DefaultKeyFactory(composes: IndexUtil)(
  srcIdAlias: String = "SrcId",
  srcIdClass: ClName = classOf[SrcId].getName
) extends KeyFactory {
  def rawKey(className: String): AssembledKey =
    composes.joinKey(was = false, srcIdAlias, srcIdClass, className)
}
