package ee.cone.c4actor.dep

import ee.cone.c4actor.dep.CtxType.Request

case class Resolvable[+A](value: Option[A], requests: Seq[Request] = Nil)

case class UpResolvable(request: RequestWithSrcId, resolvable: Resolvable[_])