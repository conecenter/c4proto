package ee.cone.c4actor.dependancy

case class Resolvable[+A](value: Option[A], requests: Seq[DepRequest[_]] = Nil)

case class UpResolvable(request: DepRequest[_], resolvable: Resolvable[_])