
package ee.cone.c4actor.dep_impl

import collection.immutable.Seq
import ee.cone.c4actor.dep.DepTypes.{DepCtx, DepRequest}
import ee.cone.c4actor.dep._
import ee.cone.c4di._

abstract class DepImpl[A] extends Dep[A] {
  def flatMap[B](f: A => Dep[B]): Dep[B] = new ComposedDep[A, B](this, f)
  def map[B](f: A => B): Dep[B] = new ComposedDep[A, B](this, v => new ResolvedDep(f(v)))
  // def filter(p: A => Boolean): Dep[A] = new FilterDep[A](this, p)
}

class FilterDep[A](innerDep: Dep[A], filter: A => Boolean) extends DepImpl[A] {
  def resolve(ctx: DepCtx): Resolvable[A] = {
    val resolved: Resolvable[A] = innerDep.resolve(ctx)
    if (resolved.value.exists(filter)) {
      resolved
    } else {
      Resolvable[A](None, resolved.requests)
    }
  }
}

class ResolvedDep[A](value: A) extends DepImpl[A] {
  def resolve(ctx: DepCtx): Resolvable[A] = Resolvable(Option(value))
}

class UnresolvedDep[A]() extends DepImpl[A] {
  def resolve(ctx: DepCtx): Resolvable[A] = Resolvable(None)
}

class ComposedDep[A, B](inner: Dep[A], fm: A => Dep[B]) extends DepImpl[B] {
  def resolve(ctx: DepCtx): Resolvable[B] =
    inner.resolve(ctx) match {
      case Resolvable(Some(v), requests) =>
        val res = fm(v.asInstanceOf[A]).resolve(ctx)
        res.copy(requests = res.requests ++ requests)
      case Resolvable(None, requests) =>
        Resolvable[Nothing](None, requests)
    }
}

class RequestDep[A](val request: DepRequest) extends DepImpl[A] {
  def resolve(ctx: DepCtx): Resolvable[A] =
    Resolvable(ctx.get(request).asInstanceOf[Option[A]], Seq(request))
}

class OptionalDep[A](dep: Dep[A]) extends DepImpl[Option[A]] {
  def resolve(ctx: DepCtx): Resolvable[Option[A]] = {
    val resolvable = dep.resolve(ctx)
    Resolvable[Option[A]](Some(resolvable.value), resolvable.requests)
  }
}

class ParallelDep[A, B](aDep: Dep[A], bDep: Dep[B]) extends DepImpl[(A, B)] {
  def resolve(ctx: DepCtx): Resolvable[(A, B)] = {
    val aRsv = aDep.resolve(ctx)
    val bRsv = bDep.resolve(ctx)
    val value: Option[(A, B)] = aRsv.value.flatMap(a => bRsv.value.map(b => (a, b)))
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

/*
  Resolved if at least one is resolved
 */
class SeqOptionParallelDep[A](depSeq: Seq[Dep[A]]) extends DepImpl[Seq[Option[A]]] {
  def resolve(ctx: DepCtx): Resolvable[Seq[Option[A]]] = {
    val seqResolved: Seq[Resolvable[A]] = depSeq.map(_.resolve(ctx))
    val valueSeq: Seq[Option[A]] = seqResolved.map(_.value)
    val atLestOneIsResolved: Boolean = valueSeq.collectFirst{case Some(a) => a}.isDefined
    val requestSeq: Seq[DepRequest] = seqResolved.flatMap(_.requests)
    val resolvedSeq = if (atLestOneIsResolved) Some(valueSeq) else None
    Resolvable[Seq[Option[A]]](resolvedSeq, requestSeq)
  }
}

/*
  Preserves order but ignores unresolved in output
 */
class SeqUncheckedParallelDep[A](depSeq: Seq[Dep[A]]) extends DepImpl[Seq[A]] {
  def resolve(ctx: DepCtx): Resolvable[Seq[A]] = {
    val seqResolved: Seq[Resolvable[A]] = depSeq.map(_.resolve(ctx))
    val valueSeq: Seq[A] = seqResolved.collect{case r if r.value.isDefined => r.value.get}
    val requestSeq: Seq[DepRequest] = seqResolved.flatMap(_.requests)
    Resolvable[Seq[A]](Option(valueSeq), requestSeq)
  }
}

@c4("DepAssembleCompApp") final case class DepFactoryImpl() extends DepFactory {
  def parallelSeq[A](value: Seq[Dep[A]]): Dep[Seq[A]] =
    new SeqParallelDep[A](value.asInstanceOf[Seq[Dep[A]]])
  def uncheckedRequestDep[Out](request: DepRequest): Dep[Out] =
    new RequestDep[Out](request)

  def resolvedRequestDep[Out](response: Out): Dep[Out] =
    new ResolvedDep[Out](response)

  def unresolvedDep[Out]: Dep[Out] =
    new UnresolvedDep[Out]()

  def parallelTuple[A, B](a: Dep[A], b: Dep[B]): Dep[(A, B)] =
    new ParallelDep[A, B](a, b)

  def parOptSeq[A](value: Seq[Dep[A]]): Dep[Seq[Option[A]]] =
    new SeqOptionParallelDep[A](value)

  def parUnsafeSeq[A](value: Seq[Dep[A]]): Dep[Seq[A]] =
    new SeqUncheckedParallelDep[A](value)

  def optDep[A](value: Dep[A]): Dep[Option[A]] =
    new OptionalDep[A](value)
}
