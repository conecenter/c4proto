package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.{AssembledKey, IndexUtil}

trait OrigKeyFactory {
  def rawKey(className: String): AssembledKey
}

trait DefaultKeyFactoryApp {
  def indexUtil: IndexUtil

  def origKeyFactory: OrigKeyFactory = DefaultOrigKeyFactory(indexUtil)
}

case class DefaultOrigKeyFactory(composes: IndexUtil) extends OrigKeyFactory {
  def rawKey(className: String): AssembledKey =
    composes.joinKey(was = false, "SrcId", classOf[SrcId].getName, className)
}
