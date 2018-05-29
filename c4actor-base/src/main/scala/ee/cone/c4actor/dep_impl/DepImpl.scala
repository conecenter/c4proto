
package ee.cone.c4actor.dep_impl

import collection.immutable.Seq
import ee.cone.c4actor.dep.DepTypes.{DepCtx, DepRequest}
import ee.cone.c4actor.dep._

abstract class DepImpl[A] extends Dep[A] {
  def flatMap[B](f: A ⇒ Dep[B]): Dep[B] = new ComposedDep[A, B](this, f)
  def map[B](f: A ⇒ B): Dep[B] = new ComposedDep[A, B](this, v ⇒ new ResolvedDep(f(v)))
}

class ResolvedDep[A](value: A) extends DepImpl[A] {
  def resolve(ctx: DepCtx): Resolvable[A] = Resolvable(Option(value))
}

class ComposedDep[A, B](inner: Dep[A], fm: A ⇒ Dep[B]) extends DepImpl[B] {
  def resolve(ctx: DepCtx): Resolvable[B] =
    inner.resolve(ctx) match {
      case Resolvable(Some(v), requests) ⇒
        val res = fm(v.asInstanceOf[A]).resolve(ctx)
        res.copy(requests = res.requests ++ requests)
      case Resolvable(None, requests) ⇒
        Resolvable[Nothing](None, requests)
    }
}

class RequestDep[A](val request: DepRequest) extends DepImpl[A] {
  def resolve(ctx: DepCtx): Resolvable[A] =
    Resolvable(ctx.get(request).asInstanceOf[Option[A]], Seq(request))
}



class ParallelDep[A, B](aDep: Dep[A], bDep: Dep[B]) extends DepImpl[(A, B)] {
  def resolve(ctx: DepCtx): Resolvable[(A, B)] = {
    val aRsv = aDep.resolve(ctx)
    val bRsv = bDep.resolve(ctx)
    val value: Option[(A, B)] = aRsv.value.flatMap(a ⇒ bRsv.value.map(b ⇒ (a, b)))
    Resolvable[(A, B)](value, aRsv.requests ++ bRsv.requests)
  }
}

class SeqParallelDep[A](depSeq: Seq[Dep[A]]) extends DepImpl[Seq[A]] {
  def resolve(ctx: DepCtx): Resolvable[Seq[A]] = {
    val seqResolved: Seq[Resolvable[A]] = depSeq.map(_.resolve(ctx))
    val valueSeq: Seq[Option[A]] = seqResolved.map(_.value)
    val resolvedSeq: Option[Seq[A]] = if (valueSeq.forall(opt => opt.isDefined))
      Some(valueSeq.map(_.get))
    else
      None
    val requestSeq: Seq[DepRequest] = seqResolved.flatMap(_.requests)
    Resolvable[Seq[A]](resolvedSeq, requestSeq)
  }
}

case class DepFactoryImpl() extends DepFactory {
  def parallelSeq[A](value: Seq[Dep[A]]): Dep[Seq[A]] =
    new SeqParallelDep[A](value.asInstanceOf[Seq[Dep[A]]])
}


case class DepAskImpl[In<:Product,Out](name: String)(val inClass: Class[In]) extends DepAsk[In,Out] {
  def ask: In ⇒ Dep[Out] = request ⇒ new RequestDep[Out](request)
}

case class DepAskFactoryImpl() extends DepAskFactory {
  def forClasses[In<:Product,Out](in: Class[In], out: Class[Out]): DepAsk[In,Out] =
    DepAskImpl[In,Out](in.getName)(in)
}

case class DepHandlerImpl(className: String)(val handle: DepRequest ⇒ DepCtx ⇒ Resolvable[_]) extends DepHandler

case class DepHandlerFactoryImpl() extends DepHandlerFactory {
  def by[In<:Product,Out](ask: DepAsk[In,Out])(handler: In ⇒ DepCtx ⇒ Resolvable[Out]): DepHandler =
    ask match {
      case a: DepAskImpl[_,_] ⇒
        DepHandlerImpl(a.name)(handler.asInstanceOf[DepRequest ⇒ DepCtx ⇒ Resolvable[_]])
    }
}
