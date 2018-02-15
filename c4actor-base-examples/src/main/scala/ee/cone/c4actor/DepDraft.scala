package ee.cone.c4actor

import ee.cone.c4actor.CtxType.Ctx
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dependancy._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by, was}

// sbt ~'c4actor-base-examples/run-main ee.cone.c4actor.DepDraft'
object DepDraft {

  def parallel[A, B](a: Dep[A], b: Dep[B]): Dep[(A, B)] =
    new ParallelDep(a.asInstanceOf[InnerDep[A]], b.asInstanceOf[InnerDep[B]])

  case class FooDepRequest(srcId: SrcId, v: String, prevSrcId: List[SrcId] = Nil) extends AbstractDepRequest[Int] {
    def extendPrev(id: SrcId): DepRequest[Int] = FooDepRequest(srcId, v, id :: prevSrcId)
  }

  case class RootDepRequest(srcId: SrcId, v: String, prevSrcId: List[SrcId] = Nil) extends AbstractDepRequest[Int] {
    def extendPrev(id: SrcId): DepRequest[Int] = RootDepRequest(srcId, v, id :: prevSrcId)
  }

  def askFoo(v: String): Dep[Int] = new RequestDep(FooDepRequest(s"$v-id", v))

  /*
    def parallelView: Dep[(Int,Int)] = for {
      (a,b) ← parallel(askFoo("A"), askFoo("B"))
    } yield (a,b)*/

  case object FooRequestHandler extends RequestHandler[FooDepRequest] {
    def canHandle = classOf[FooDepRequest]

    def handle: FooDepRequest => ResolvedDep[Int] = fooRq => {
      val response = fooRq.v match {
        case "A" => 1
        case "B" => 2
        case "C" => 3
        case "D" => 10
        case a ⇒ throw new Exception(s"$a can't be handled by FooRequestHandler")
      }
      new ResolvedDep(response)
    }
  }

  case object RootRequestHandler extends RequestHandler[RootDepRequest] {
    def canHandle = classOf[RootDepRequest]

    def handle: RootDepRequest => Dep[(Int, Int, Int)] = _ => serialView
  }

  def subView(a: Int): Dep[Int] = for {
    c ← askFoo("D")
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
    val r1: Ctx = Map()
    println(serialView.asInstanceOf[InnerDep[_]].resolve(r1))
    val r2 = r1 + ("A-id" → Some(1))
    println(serialView.asInstanceOf[InnerDep[_]].resolve(r2))
    val r3 = r2 + ("C-id" → Some(3))
    println(serialView.asInstanceOf[InnerDep[_]].resolve(r3))
    val r4 = r3 + ("B-id" → Some(2))
    println(serialView.asInstanceOf[InnerDep[_]].resolve(r4))
    val r5 = r4 + ("D-id" → Some(10))
    println(serialView.asInstanceOf[InnerDep[_]].resolve(r5))
  }

  def buildContext: Values[Response] => Ctx = _.map(curr ⇒ (curr.request.srcId, curr.value)).toMap
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



