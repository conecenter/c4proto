package ee.cone.c4actor

import ee.cone.c4assemble.All

object WithAll {
    def apply[P<:Product](p: P): (All,P) = All → p
}