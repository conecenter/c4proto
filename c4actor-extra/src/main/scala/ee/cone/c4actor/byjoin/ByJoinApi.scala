package ee.cone.c4actor.byjoin

import ee.cone.c4actor.ProdLens
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.{Assemble, EachSubAssemble, ValuesSubAssemble}

import scala.reflect.ClassTag

trait ByJoinApp {
  def byJoinFactory: ByJoinFactory
}

trait ByPKJoin[From <: Product, Value] {
  def toEach[To <: Product](implicit ct: ClassTag[To]): EachSubAssemble[To] with Assemble
  def toValues[To <: Product](implicit ct: ClassTag[To]): ValuesSubAssemble[To] with Assemble
}

trait ByJoinFactory {
  def byPK[From <: Product](lens: ProdLens[From, List[SrcId]])(implicit ct: ClassTag[From]): ByPKJoin[From, List[SrcId]]
}
