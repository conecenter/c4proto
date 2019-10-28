package ee.cone.c4actor

import ee.cone.c4assemble.{AbstractAll, All}

object WithAll {
    def apply[P<:Product](p: P): (AbstractAll,P) = All -> p
}