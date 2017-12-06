package ee.cone.c4gate

import ee.cone.c4assemble.{Assembled, DataDependencyTo}

case class LocalPostConsumer(condition: String)

trait WrapAssembled extends Assembled {
  def inner: Assembled
  def dataDependencies: List[DataDependencyTo[_]] = inner.dataDependencies
}