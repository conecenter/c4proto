package ee.cone.c4gate

import ee.cone.c4actor.{AssemblesApp, GzipFullCompressor}
import ee.cone.c4assemble.{Assemble, IndexUtil, ReadModelUtil}

trait ActorAccessApp extends ActorAccessAutoApp with AssemblesApp {
  override def assembles: List[Assemble] =
    new ActorAccessAssemble :: super.assembles
}

trait PrometheusApp extends AssemblesApp {
  def indexUtil: IndexUtil
  def readModelUtil: ReadModelUtil

  override def assembles: List[Assemble] =
    new PrometheusAssemble(GzipFullCompressor(), indexUtil, readModelUtil) :: super.assembles
}

trait AvailabilityApp extends AvailabilityAutoApp with AssemblesApp {
  def availabilityDefaultUpdatePeriod: Long = 3000
  def availabilityDefaultTimeout: Long = 3000

  override def assembles: List[Assemble] =
    new AvailabilityAssemble(availabilityDefaultUpdatePeriod, availabilityDefaultTimeout) :: super.assembles
}

