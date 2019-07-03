package ee.cone.c4actor

import ee.cone.c4actor.Types.{ClName, SrcId}
import ee.cone.c4assemble.{AssembledKey, IndexUtil}

trait DefaultKeyFactoryApp {
  def indexUtil: IndexUtil

  def byPKKeyFactory: KeyFactory = DefaultKeyFactory(indexUtil)
  def origKeyFactoryOpt: Option[KeyFactory] = None
}

case class DefaultKeyFactory(composes: IndexUtil) extends KeyFactory {
  val srcIdAlias: String = "SrcId"
  val srcIdClass: ClName = classOf[SrcId].getName

  def rawKey(className: String): AssembledKey =
    composes.joinKey(was = false, srcIdAlias, srcIdClass, className)
}
