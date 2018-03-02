package ee.cone.c4actor.dep

import ee.cone.c4actor.dep.CtxType.DepRequest

case class Resolvable[+A](value: Option[A], requests: Seq[DepRequest] = Nil)

case class UpResolvable(request: DepRequestWithSrcId, resolvable: Resolvable[_])