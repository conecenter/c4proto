package ee.cone.c4actor

import ee.cone.c4actor.Types.{ClName, SrcId}
import ee.cone.c4assemble.{AssembledKey, IndexUtil}

trait DefaultKeyFactoryApp {
  def indexUtil: IndexUtil

  def origKeyFactory: KeyFactory = DefaultKeyFactory(indexUtil)
}

case class DefaultKeyFactory(composes: IndexUtil) extends KeyFactory {
  val srcIdAlias: String = "SrcId"
  val srcIdClass: ClName = classOf[SrcId].getName

  def rawKey(className: String): AssembledKey =
    srcIdKey(className)

  def srcIdKey(className: String): AssembledKey =
    composes.joinKey(was = false, srcIdAlias, srcIdClass, className)
}
