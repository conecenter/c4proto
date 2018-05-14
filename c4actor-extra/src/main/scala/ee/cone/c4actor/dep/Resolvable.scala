package ee.cone.c4actor.dep

import ee.cone.c4actor.{LazyHashCodeProduct, PreHashed}
import ee.cone.c4actor.dep.DepTypeContainer.DepRequest

case class Resolvable[+A](value: Option[A], requests: Seq[DepRequest] = Nil) extends LazyHashCodeProduct

case class DepResolvable(request: DepOuterRequest, resolvableHashed: PreHashed[Resolvable[_]]) extends LazyHashCodeProduct {
  lazy val resolvable: Resolvable[_] = resolvableHashed.value
}