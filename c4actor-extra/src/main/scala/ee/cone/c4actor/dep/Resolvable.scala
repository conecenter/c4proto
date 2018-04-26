package ee.cone.c4actor.dep

import ee.cone.c4actor.dep.DepTypeContainer.DepRequest

case class Resolvable[+A](value: Option[A], requests: Seq[DepRequest] = Nil)

case class DepResolvable(request: DepOuterRequest, resolvable: Resolvable[_])