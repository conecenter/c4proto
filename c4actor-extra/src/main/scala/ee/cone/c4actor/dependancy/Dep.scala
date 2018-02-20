package ee.cone.c4actor.dependancy

import ee.cone.c4actor.CtxType.{Ctx, Request}

trait Dep[A] {
  def flatMap[B](f: A ⇒ Dep[B]): Dep[B]

  def map[B](f: A ⇒ B): Dep[B]
}

trait InnerDep[B] extends Dep[B] {
  def resolve(ctx: Ctx): Resolvable[B]
}

abstract class DepImpl[A] extends InnerDep[A] {
  def flatMap[B](f: A ⇒ Dep[B]): Dep[B] = new ComposedDep[A, B](this, f)

  def map[B](f: A ⇒ B): Dep[B] = new ComposedDep[A, B](this, v ⇒ new ResolvedDep(f(v)))
}

class ResolvedDep[A](value: A) extends DepImpl[A] {
  def resolve(ctx: Ctx): Resolvable[A] = Resolvable(Option(value))
}

class ComposedDep[A, B](inner: InnerDep[A], fm: A ⇒ Dep[B]) extends DepImpl[B] {
  def resolve(ctx: Ctx): Resolvable[B] =
    inner.resolve(ctx) match {
      case Resolvable(Some(v), requests) ⇒
        val res = fm(v.asInstanceOf[A]).asInstanceOf[InnerDep[B]].resolve(ctx)
        res.copy(requests = res.requests ++ requests)
      case Resolvable(None, requests) ⇒
        Resolvable[Nothing](None, requests)
    }
}

class RequestDep[A](request: Request) extends DepImpl[A] {
  def resolve(ctx: Ctx): Resolvable[A] =
    Resolvable(ctx.getOrElse(request, None).asInstanceOf[Option[A]], Seq(request))
}

class ParallelDep[A, B](aDep: InnerDep[A], bDep: InnerDep[B]) extends DepImpl[(A, B)] {
  def resolve(ctx: Ctx): Resolvable[(A, B)] = {
    val aRsv = aDep.resolve(ctx)
    val bRsv = bDep.resolve(ctx)
    val value: Option[(A, B)] = aRsv.value.flatMap(a ⇒ bRsv.value.map(b ⇒ (a,b)))
    Resolvable[(A,B)](value, aRsv.requests ++ bRsv.requests)
  }
}

case class UnresolvedDep(rq: RequestWithSrcId, resolvable: UpResolvable)