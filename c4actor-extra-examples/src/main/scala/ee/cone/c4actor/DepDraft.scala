package ee.cone.c4actor

import ee.cone.c4actor.TestProtocol.ValueNode
import ee.cone.c4actor.TestRequests.FooDepRequest
import ee.cone.c4actor.dep.DepTypeContainer.{ContextId, DepCtx}
import ee.cone.c4actor.dep._
import ee.cone.c4actor.dep.request.ByPKRequestProtocol.ByPKRequest
import ee.cone.c4proto.{Id, Protocol, protocol}

// sbt ~'c4actor-extra-examples/run-main ee.cone.c4actor.DepDraft'
case class DepDraft(factory : CommonRequestUtilityFactory) {

  def parallel[A, B](a: Dep[A], b: Dep[B]): Dep[(A, B)] =
    new ParallelDep(a.asInstanceOf[InnerDep[A]], b.asInstanceOf[InnerDep[B]])

  def askFoo(v: String): Dep[Int] = new RequestDep[Int](FooDepRequest(v))

  /*
    def parallelView: Dep[(Int,Int)] = for {
      (a,b) ← parallel(askFoo("A"), askFoo("B"))
    } yield (a,b)*/

  case object FooRequestHandler extends RequestHandler[FooDepRequest] {
    def canHandle = classOf[FooDepRequest]

    def handle: FooDepRequest => (ResolvedDep[Int], ContextId) = fooRq => {
      val response = fooRq.v match {
        case "A" => 1
        case "B" => 2
        case "C" => 3
        case "D" => 10
        case a ⇒ throw new Exception(s"$a can't be handled by FooRequestHandler")
      }
      (new ResolvedDep(response), "")
    }
  }

  import factory._

  def testList: Dep[Int] = for {
    list ← askByClassName(classOf[ValueNode], -1, -1)
  } yield {
    println(list)
    list.foldLeft(0)((sum, node) ⇒ sum + node.value)}

  def testView: Dep[Int] = for {
    a ← askByPK(classOf[ValueNode], "123")
  } yield a.map(_.value).getOrElse(0)

  def subView(a: Int): Dep[Int] = for {
    c ← askByPK(classOf[ValueNode], "123")
    b ← askFoo("B")
  } yield a + b + c.map(_.value).getOrElse(0)

  def serialView: Dep[(Int, Int, Int)] = for {
    a ← askFoo("A")
    s ← subView(a)
    b ← askByPK(classOf[ValueNode], "124")
  } yield (a, s, b.map(_.value).getOrElse(0))

  /*
    def parallelView: Dep[(Int,Int)] = for {
      (a,b) ← parallel(askFoo("A"), askFoo("B"))
    } yield (a,b)*/

  def main(args: Array[String]): Unit = {
    serialView.asInstanceOf[InnerDep[_]]
    val test = serialView.asInstanceOf[InnerDep[_]]
    val r1: DepCtx = Map()
    println(serialView.asInstanceOf[InnerDep[_]].resolve(r1))
    val r2 = r1 + (FooDepRequest("A") → Some(1))
    println(serialView.asInstanceOf[InnerDep[_]].resolve(r2))
    val r3 = r2 + (ByPKRequest(classOf[ValueNode].getName, "123") → Some(Option(ValueNode("123", 100))))
    println(serialView.asInstanceOf[InnerDep[_]].resolve(r3))
    val r4 = r3 + (FooDepRequest("B") → Some(2))
    println(serialView.asInstanceOf[InnerDep[_]].resolve(r4))
    val r5 = r4 + (FooDepRequest("D") → Some(10))
    println(serialView.asInstanceOf[InnerDep[_]].resolve(r5))
  }
}

@protocol object TestRequests extends Protocol {
  @Id(0x3031) case class FooDepRequest(@Id(0x3036) v: String)
}


