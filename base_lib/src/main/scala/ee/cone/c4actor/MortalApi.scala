package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Assemble

object LifeTypes {
  type Alive = SrcId
}

trait MortalFactory {
  def apply[P<:Product](p: Class[P]): Assemble
}
