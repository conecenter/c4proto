package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by, was}

// sbt ~'c4actor-base-examples/run-main ee.cone.c4actor.DepDraft'
object DepDraft {

  type Ctx = Map[SrcId, _]

  trait DepRequest extends Product

  trait Request[A] extends Lens[Ctx, Option[A]] with DepRequest{
    val srcId: SrcId
    val prevSrcId: List[SrcId]
    def extendPrev(id: SrcId): Request[A]
  }

  abstract class AbstractRequest[A] extends AbstractLens[Ctx, Option[A]] with Request[A] with Product {
    def of: Ctx ⇒ Option[A] = ctx ⇒ ctx.getOrElse(this.srcId, None).asInstanceOf[Option[A]]

    def set: Option[A] ⇒ Ctx ⇒ Ctx = value ⇒ ctx ⇒ ctx + (this.srcId → value)
  }

  case class UpResolvable(request: Request[_], resolvable: Resolvable[_])

  case class Resolvable[+A](value: Option[A], requests: Seq[Request[_]] = Nil)

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

  class RequestDep[A](request: Request[A]) extends DepImpl[A] {
    def resolve(ctx: Ctx): Resolvable[A] =
      Resolvable(request.of(ctx), Seq(request))
  }

  class ParallelDep[A, B](aDep: InnerDep[A], bDep: InnerDep[B]) extends DepImpl[(A, B)] {
    def resolve(ctx: Ctx): Resolvable[(A, B)] = {
      ???
      /*(aDep.resolve(ctx), bDep.resolve(ctx)) match {
        case (NotResolved(aRequests),NotResolved(bRequests)) ⇒
      }*/
    }
  }

  def parallel[A, B](a: Dep[A], b: Dep[B]): Dep[(A, B)] =
    new ParallelDep(a.asInstanceOf[InnerDep[A]], b.asInstanceOf[InnerDep[B]])

  case class FooRequest(srcId: SrcId, v: String, prevSrcId: List[SrcId] = Nil) extends AbstractRequest[Int] {
    def extendPrev(id: SrcId): Request[Int] = FooRequest(srcId, v, id::prevSrcId)
  }

  case class RootRequest(srcId: SrcId, v: String, prevSrcId: List[SrcId] = Nil) extends AbstractRequest[Int] {
    def extendPrev(id: SrcId): Request[Int] = RootRequest(srcId, v, id::prevSrcId)
  }

  def askFoo(v: String): Dep[Int] = new RequestDep(FooRequest(s"$v-id", v))

  /*
    def parallelView: Dep[(Int,Int)] = for {
      (a,b) ← parallel(askFoo("A"), askFoo("B"))
    } yield (a,b)*/

  trait RequestHandler[A] {
    def isDefinedAt: Class[A]

    def handle: A => Dep[_]
  }


  case object FooRequestHandler extends RequestHandler[FooRequest] {
    def isDefinedAt = classOf[FooRequest]

    def handle: FooRequest => ResolvedDep[Int] = fooRq => {
      val response = fooRq.v match {
        case "A" => 1
        case "B" => 2
        case "C" => 3
      }
      new ResolvedDep(response)
    }

  }

  case object RootRequestHandler extends RequestHandler[RootRequest] {
    def isDefinedAt = classOf[RootRequest]

    def handle: RootRequest => Dep[(Int, Int, Int)] = _ => serialView
  }

  val depHandlerRegistry = List(RootRequestHandler, FooRequestHandler)

  def subView(a: Int): Dep[Int] = for {
    c ← askFoo("C")
    b ← askFoo("B")
  } yield a + b + c

  def serialView: Dep[(Int, Int, Int)] = for {
    a ← askFoo("A")
    s ← subView(a)
    b ← askFoo("B")
  } yield (a, s, b)

  /*
    def parallelView: Dep[(Int,Int)] = for {
      (a,b) ← parallel(askFoo("A"), askFoo("B"))
    } yield (a,b)*/

  def main(args: Array[String]): Unit = {
    serialView.asInstanceOf[InnerDep[_]]
    val test = serialView.asInstanceOf[InnerDep[_]]
    val r1: DepDraft.Ctx = Map()
    println(serialView.asInstanceOf[InnerDep[_]].resolve(r1))
    val r2 = r1 + ("A-id" → 1)
    println(serialView.asInstanceOf[InnerDep[_]].resolve(r2))
    val r3 = r2 + ("C-id" → 3)
    println(serialView.asInstanceOf[InnerDep[_]].resolve(r3))
    val r4 = r3 + ("B-id" → 2)
    println(serialView.asInstanceOf[InnerDep[_]].resolve(r4))
    val fooRq = FooRequest("A-id", "A")
    println(FooRequestHandler.handle(fooRq).asInstanceOf[InnerDep[_]].resolve(Map()))
  }

  case class Response(request: Request[_], value: Option[_], rqList: List[SrcId] = Nil)

  def buildContext: Values[Response] => Ctx = _.map(curr ⇒ (curr.request.srcId, curr.value)).toMap//_.foldLeft[Ctx](Map())((curr, z: Response) => curr ++ z.requests.map(rq ⇒ (rq, z.value)).toMap) //TODO use toMap

}

/*
requests:
  External DBs
  ByPK/Key
  HashSearch
  Filters
  Pivots
class DepTestAssemble {
  def responses
  (
    @was @by[Select] requests: Values[Request],
    foos: Values[Foo]
  ): Values[(Requester,Response)]
  def view
  (
    rootRequest: Values[RootRequest],
    @by[Requester] responses: Values[Response]
  ): Values[Resolvable]
  def prepResolve
  (
    resolvable: Values[Resolvable]
  ): Values[(Select,Request)]
}
RequestHandler[Req,Resp] {
  def handle: Req => Dep[Resp]
}
FooRequestHandler(BarRequestHandler){
  def handle: BarRequestHandler =>  FooDep[asdsad]
}
FooFactoryApp {
  def barFactory
  lazy val fooFactory = FooFactory(barFactory)
}
FooFactory(barFactory) {
  def getFoo(a): Dep[Foo] = for {
    bar <- barFactory.getBar(6)
  } yield f(foo,bar)
}
FooFactoryApp {
  def barFactory
  lazy val fooFactory = FooFactory()
  override def depHandlers = FooHandler(barFactory) :: super.depHandlers
}
case class FooReq(a)
FooFactory(requestDep) {
  def getFoo(a): Dep[Foo] = requestDep(FooReq(a))
}
FooHandler(barFactory) {
  def matcher = classOf[FooReq]
  def handle: FooReq => Dep[Foo] = for {
    bar <- barFactory.getBar(6)
  } yield f(foo,bar)
}
BarHandlerApp {
  def requestMotherFactory
  handlerDeps
  private lazy val aaa = requestMotherFactory.create(classOf[FooReq])
  override def requestFactoryList = aaa :: super...
  lazy val barHandler = BarHandler(aaa)
  override def requestHandlers = createHandler(barHandler, classOf[BarReq]) :: super...
}
BarHandler(
  askFoo: RequestFactory[FooReq]
) extends Handler[BarReq] {
  def handle = req => for {
    foo <- askFoo(FooReq(1,2))
  }
}
FooHandler(barFactory) {
  def matcher = classOf[FooReq]
  def handle: FooReq => Dep[Foo] = for {
    bar <- barFactory.getBar(6)
  } yield f(foo,bar)
}
*/
// FooDep.resolve(_) = Resolvable(Some(FooBar),Nil)
//lazy val a: Int=>Int = identity



