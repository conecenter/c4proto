package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.{AssembledKey, IndexUtil, OrigKeyFactory}

trait OrigKeyFactoryApp {
  def indexUtil: IndexUtil

  def origKeyFactory: OrigKeyFactory = OrigKeyFactoryImpl(indexUtil)
}

case class OrigKeyFactoryImpl(composes: IndexUtil) extends OrigKeyFactory {
  def rawKey(className: String): AssembledKey =
    composes.joinKey(was = false, "SrcId", classOf[SrcId].getName, className)
}
