package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Assembled

object LifeTypes {
  type Alive = SrcId
}

@c4component @listed abstract class Mortal[M<:Product](val theClass: Class[M])
