package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.{AssembledKey, IndexUtil}

trait DefaultKeyFactoryApp {
  def indexUtil: IndexUtil

  def origKeyFactory: KeyFactory = DefaultKeyFactory(indexUtil)
}

case class DefaultKeyFactory(composes: IndexUtil) extends KeyFactory {
  def rawKey(className: String): AssembledKey =
    composes.joinKey(was = false, "SrcId", classOf[SrcId].getName, className)
}
