package ee.cone.c4actor

import ee.cone.c4actor.CtxType.Ctx
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, was}

package object CtxType {
  type Ctx = Map[Request, _]
  type Request = Product
}