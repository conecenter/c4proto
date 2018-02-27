package ee.cone.c4actor

import java.io.{ByteArrayOutputStream, ObjectOutputStream}
import java.nio.ByteBuffer

import ee.cone.c4actor.CtxType.{ContextId, Ctx}
import ee.cone.c4actor.LULProtocol.PffNode
import ee.cone.c4actor.TestRequests.{FooDepRequest, RootDepRequest}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dependancy.ByClassNameRequestProtocol.ByClassNameRequest
import ee.cone.c4actor.dependancy.ByPKRequestProtocol.ByPKRequest
import ee.cone.c4actor.dependancy._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4actor.dependancy.SessionAttrRequestUtility.askSessionAttr
import ee.cone.c4gate.SessionAttr

// sbt ~'c4actor-extra-examples/run-main ee.cone.c4actor.DepDraft'
object DepDraft {

  def parallel[A, B](a: Dep[A], b: Dep[B]): Dep[(A, B)] =
    new ParallelDep(a.asInstanceOf[InnerDep[A]], b.asInstanceOf[InnerDep[B]])

  def askFoo(v: String): Dep[Int] = new RequestDep[Int](FooDepRequest(v))

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

    def handle: RootDepRequest => (Dep[_], ContextId) = _ => testSession
  }

  def askPyPK[A](className: String, srcId: SrcId) = new RequestDep[Option[A]](ByPKRequest(className, srcId))

  def askByClassName[A](className: String, from: Int = -1, to: Int = -1) = new RequestDep[List[A]](ByClassNameRequest(className, from, to))

  def testSession = for{
    accessOpt ← askSessionAttr(SessionAttr[PffNode](classOf[PffNode].getName, 0x0f1a, "", NameMetaAttr(0x0f1a.toString) :: Nil))
  } yield {
    accessOpt
  }

  def testList: Dep[Int] = for {
    list ← askByClassName[PffNode](classOf[PffNode].getName, -1, -1)
  } yield {
    println(list)
    list.foldLeft(0)((sum, node) ⇒ sum + node.value)}

  def testView: Dep[Int] = for {
    a ← askPyPK[PffNode](PffNode.getClass.getName, "123")
  } yield a.map(_.value).getOrElse(0)

  def subView(a: Int): Dep[Int] = for {
    c ← askPyPK[PffNode](PffNode.getClass.getName, "123")
    b ← askFoo("B")
  } yield a + b + c.map(_.value).getOrElse(0)

  def serialView: Dep[(Int, Int, Int)] = for {
    a ← askFoo("A")
    s ← subView(a)
    b ← askPyPK[PffNode](PffNode.getClass.getName, "124")
  } yield (a, s, b.map(_.value).getOrElse(0))

  /*
    def parallelView: Dep[(Int,Int)] = for {
      (a,b) ← parallel(askFoo("A"), askFoo("B"))
    } yield (a,b)*/

  def main(args: Array[String]): Unit = {
    serialView.asInstanceOf[InnerDep[_]]
    val test = serialView.asInstanceOf[InnerDep[_]]
    val r1: Ctx = Map()
    println(serialView.asInstanceOf[InnerDep[_]].resolve(r1))
    val r2 = r1 + (FooDepRequest("A") → Some(1))
    println(serialView.asInstanceOf[InnerDep[_]].resolve(r2))
    val r3 = r2 + (ByPKRequest(classOf[PffNode].getName, "123") → Some(Option(PffNode("123", 100))))
    println(serialView.asInstanceOf[InnerDep[_]].resolve(r3))
    val r4 = r3 + (FooDepRequest("B") → Some(2))
    println(serialView.asInstanceOf[InnerDep[_]].resolve(r4))
    val r5 = r4 + (FooDepRequest("D") → Some(10))
    println(serialView.asInstanceOf[InnerDep[_]].resolve(r5))
  }

  def buildContext: Values[Response] => Ctx = _.map(curr ⇒ (curr.request.request, curr.value)).toMap

  def serialise(value: Any): Array[Byte] = {
    val stream: ByteArrayOutputStream = new ByteArrayOutputStream()
    val oos = new ObjectOutputStream(stream)
    oos.writeObject(value)
    oos.close()
    stream.toByteArray
  }

  def toBytes(value: Long) =
    ByteBuffer.allocate(java.lang.Long.BYTES).putLong(value).array()
}

@protocol object TestRequests extends Protocol {
  @Id(0x3031) case class FooDepRequest(@Id(0x3036) v: String)

  @Id(0x3032) case class RootDepRequest(@Id(0x3037) v: String)
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



