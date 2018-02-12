package ee.cone.c4actor

// sbt ~'c4actor-base-examples/run-main ee.cone.c4actor.DepDraft'
object DepDraft {

  type Ctx = Map[Request[_],_]

  trait Request[A] extends Lens[Ctx,Option[A]]

  abstract class AbstractRequest[A] extends AbstractLens[Ctx,Option[A]] with Request[A] with Product {
    def of: Ctx ⇒ Option[A] = ctx ⇒ ctx.get(this).asInstanceOf[Option[A]]
    def set: Option[A] ⇒ Ctx ⇒ Ctx = value ⇒ ctx ⇒ ctx + (this → value)
  }

  case class Resolvable[+A](value: Option[A], requests: Seq[Request[_]] = Nil)

  trait Dep[A] {
    def flatMap[B](f: A⇒Dep[B]): Dep[B]
    def map[B](f: A⇒B): Dep[B]
  }

  trait InnerDep[B] extends Dep[B] {
    def resolve(ctx: Ctx): Resolvable[B]
  }

  abstract class DepImpl[A] extends InnerDep[A] {
    def flatMap[B](f: A⇒Dep[B]): Dep[B] = new ComposedDep[A,B](this,f)
    def map[B](f: A⇒B): Dep[B] = new ComposedDep[A,B](this,v⇒new ResolvedDep(f(v)))
  }

  class ResolvedDep[A](value: A) extends DepImpl[A] {
    def resolve(ctx: Ctx): Resolvable[A] = Resolvable(Option(value))
  }

  class ComposedDep[A,B](inner: InnerDep[A], fm: A⇒Dep[B]) extends DepImpl[B] {
    def resolve(ctx: Ctx): Resolvable[B] =
      inner.resolve(ctx) match {
        case Resolvable(Some(v),requests) ⇒
          val res = fm(v.asInstanceOf[A]).asInstanceOf[InnerDep[B]].resolve(ctx)
          res.copy(requests = res.requests ++ requests)
        case Resolvable(None,requests) ⇒
          Resolvable[Nothing](None,requests)
      }
  }

  class RequestDep[A](request: Request[A]) extends DepImpl[A] {
    def resolve(ctx: Ctx): Resolvable[A] =
      Resolvable(request.of(ctx), Seq(request))
  }

  class ParallelDep[A,B](aDep: InnerDep[A], bDep: InnerDep[B]) extends DepImpl[(A,B)] {
    def resolve(ctx: Ctx): Resolvable[(A,B)] = {
      ???
      /*(aDep.resolve(ctx), bDep.resolve(ctx)) match {
        case (NotResolved(aRequests),NotResolved(bRequests)) ⇒
      }*/
    }
  }

  def parallel[A,B](a: Dep[A], b: Dep[B]): Dep[(A,B)] =
    new ParallelDep(a.asInstanceOf[InnerDep[A]],b.asInstanceOf[InnerDep[B]])

  case class FooRequest(v: String) extends AbstractRequest[Int]
  def askFoo(v: String): Dep[Int] = new RequestDep(FooRequest(v))

  def serialView: Dep[(Int,Int)] = for {
    a ← askFoo("A")
    b ← askFoo("B")
  } yield (a,b)
/*
  def parallelView: Dep[(Int,Int)] = for {
    (a,b) ← parallel(askFoo("A"), askFoo("B"))
  } yield (a,b)*/

  def main(args: Array[String]): Unit = {
    println(serialView.asInstanceOf[InnerDep[_]].resolve(Map(
    )))
    println(serialView.asInstanceOf[InnerDep[_]].resolve(Map(
      FooRequest("A") → 1
    )))
    println(serialView.asInstanceOf[InnerDep[_]].resolve(Map(
      FooRequest("A") → 1,
      FooRequest("B") → 2
    )))

  }

}



