package ee.cone.c4actor

import ee.cone.c4actor.Types.{ClName, SrcId}
import ee.cone.c4assemble.{AssembledKey, IndexUtil}
import ee.cone.c4proto.{Component, ComponentsApp, c4component}

@c4component("RichDataAutoApp") class SwitchOrigKeyFactoryHolder(overriding: OrigKeyFactoryHolder, byPKKeyFactory: KeyFactory)
  extends OrigKeyFactoryHolder(overriding.value.orElse(Option(byPKKeyFactory)))

@c4component("RichDataAutoApp") case class DefaultKeyFactory(composes: IndexUtil)(
  srcIdAlias: String = "SrcId",
  srcIdClass: ClName = classOf[SrcId].getName
) extends KeyFactory {
  def rawKey(className: String): AssembledKey =
    composes.joinKey(was = false, srcIdAlias, srcIdClass, className)
}
