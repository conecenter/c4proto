package ee.cone.c4actor

import ee.cone.c4actor.TestProtocol.D_ValueNode
import ee.cone.c4actor.TestRequests.{D_ChildDepRequest, D_FooDepRequest}
import ee.cone.c4actor.dep.DepTypes.{DepCtx, DepRequest}
import ee.cone.c4actor.dep._
import ee.cone.c4actor.dep.request.ByClassNameAllAsk
import ee.cone.c4actor.dep_impl.ByPKRequestProtocol.N_ByPKRequest
import ee.cone.c4actor.dep_impl.{RequestDep, ResolvedDep}
import ee.cone.c4proto.{Id, protocol}

// sbt ~'c4actor-extra-examples/run-main ee.cone.c4actor.DepDraft'
case class DepDraft(factory: CommonRequestUtilityFactory, valueNode: AskByPK[D_ValueNode], depAskFactory: DepAskFactory, kek: ByClassNameAllAsk, depFactory: DepFactory) {

  def askFoo(v: String): Dep[Int] = new RequestDep[Int](D_FooDepRequest(v))

  /*
    def parallelView: Dep[(Int,Int)] = for {
      (a,b) <- parallel(askFoo("A"), askFoo("B"))
    } yield (a,b)*/

  def handlerLUL: DepHandler = depAskFactory.forClasses(classOf[D_FooDepRequest], classOf[Int]).by(foo => {
    val response = foo.v match {
      case "A" => 1
      case "B" => 2
      case "C" => 3
      case "D" => 10
      case a => throw new Exception(s"$a can't be handled by FooRequestHandler")
    }
    new ResolvedDep(response)
  }
  )

  def handlerKEK: DepHandler = depAskFactory.forClasses(classOf[D_ChildDepRequest], classOf[String]).by(foo => factory.askRoleId)

  case object FooRequestHandler extends DepHandler {
    def requestClassName: String = classOf[D_FooDepRequest].getName

    def handle: DepRequest => DepCtx => Resolvable[_] = fooRq => ctx => {
      val response = fooRq.asInstanceOf[D_FooDepRequest].v match {
        case "A" => 1
        case "B" => 2
        case "C" => 3
        case "D" => 10
        case a => throw new Exception(s"$a can't be handled by FooRequestHandler")
      }
      new ResolvedDep(response).resolve(ctx)
    }
  }

  import factory._

  def testList: Dep[Int] = for {
    list <- askByClassName(classOf[D_ValueNode], -1, -1)
  } yield {
    println(list)
    list.foldLeft(0)((sum, node) => sum + node.value)
  }

  def testView: Dep[Int] = for {
    a <- valueNode.list("123")
  } yield a.map(_.value).headOption.getOrElse(0)

  def subView(a: Int): Dep[Int] = for {
    contextIdOpt <- depFactory.optDep(new RequestDep[String](D_ChildDepRequest("test")))
    c <- valueNode.list("123")
    b <- askFoo("B")
  } yield {
    PrintColored("b")(contextIdOpt)
    a + b + c.map(_.value).headOption.getOrElse(0)
  }

  def serialView: Dep[(Int, Int, Int)] = for {
    b <- depFactory.parallelTuple(askFoo("A"), factory.askRoleId)
    (a, t) = b
    seq <- depFactory.parallelSeq(askFoo("A") :: askFoo("A") :: Nil)
    Seq(i, j) = seq
    s <- subView(a)
    b <- valueNode.list("124")
    test <- kek.askByClAll(classOf[D_ValueNode])
  } yield {
    println(t, test, i, j)
    TimeColored("g", (t, test, i, j))((a, s, b.map(_.value).headOption.getOrElse(0)))
  }

  /*
    def parallelView: Dep[(Int,Int)] = for {
      (a,b) <- parallel(askFoo("A"), askFoo("B"))
    } yield (a,b)*/

  def main(args: Array[String]): Unit = {
    val test = serialView
    val r1: DepCtx = Map()
    println(serialView.resolve(r1))
    val r2 = r1 + (D_FooDepRequest("A") -> Some(1))
    println(serialView.resolve(r2))
    val r3 = r2 + (N_ByPKRequest(classOf[D_ValueNode].getName, "123") -> Some(Option(D_ValueNode("123", 100))))
    println(serialView.resolve(r3))
    val r4 = r3 + (D_FooDepRequest("B") -> Some(2))
    println(serialView.resolve(r4))
    val r5 = r4 + (D_FooDepRequest("D") -> Some(10))
    println(serialView.resolve(r5))
  }
}

trait TestRequestProtocolAppBase

@protocol("TestRequestProtocolApp") object TestRequestsBase {

  @Id(0x3031) case class D_FooDepRequest(@Id(0x3036) v: String)

  @Id(0x3333) case class D_ChildDepRequest(@Id(0x3334) v: String)

}


