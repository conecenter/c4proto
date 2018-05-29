package ee.cone.c4actor.dep_impl

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.WithPK
import ee.cone.c4actor.dep._
import ee.cone.c4actor.dep_impl.ByPKRequestProtocol.ByPKRequest
import ee.cone.c4actor.dep_impl.ByPKTypes.ByPkItemSrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by}
import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol object ByPKRequestProtocol extends Protocol {
  @Id(0x0070) case class ByPKRequest(
    @Id(0x0071) className: String,
    @Id(0x0072) itemSrcId: String
  )
}

case class InnerByPKRequest(request: DepInnerRequest, className: String)

object ByPKTypes {
  type ByPkItemSrcId = SrcId
}

@assemble class ByPKAssemble extends Assemble {
  def BPKRequestWithSrcToItemSrcId(
    key: SrcId,
    requests: Values[DepInnerRequest]
  ): Values[(ByPkItemSrcId, InnerByPKRequest)] = for {
    rq ← requests if rq.request.isInstanceOf[ByPKRequest]
  } yield {
    val brq = rq.request.asInstanceOf[ByPKRequest]
    brq.itemSrcId → InnerByPKRequest(rq, brq.className)
  }
}

@assemble class ByPKGenericAssemble[A <: Product](handledClass: Class[A], util: DepReqRespFactory) extends Assemble {
  def BPKRequestToResponse(
    key: SrcId,
    @by[ByPkItemSrcId] requests: Values[InnerByPKRequest],
    items: Values[A]
  ): Values[(SrcId, DepResponse)] = for (
    rq ← requests if rq.className == handledClass.getName
  ) yield WithPK(util.response(rq.request, Option(items)))
}

object ByPKAssembles {
  def apply(askByPKs: List[AbstractAskByPK]): List[Assemble] =
    new ByPKAssemble :: askByPKs.distinct.collect{ case bc: AskByPKImpl[_] ⇒ bc.assemble }
}

case class AskByPKFactoryImpl(depAskFactory: DepAskFactory, depReqRespFactory: DepReqRespFactory) extends AskByPKFactory {
  def forClass[A<:Product](cl: Class[A]): AskByPK[A] =
    AskByPKImpl(cl.getName, depReqRespFactory)(cl,depAskFactory.forClasses(classOf[ByPKRequest],classOf[Values[A]]))
}
case class AskByPKImpl[A<:Product](name: String, depReqRespFactory: DepReqRespFactory)(
  theClass: Class[A], depAsk: DepAsk[ByPKRequest,Values[A]]
) extends AskByPK[A] {
  def ask: SrcId ⇒ Dep[Values[A]] = id ⇒ depAsk.ask(ByPKRequest(name,id))
  def assemble: Assemble = new ByPKGenericAssemble(theClass, depReqRespFactory)
}

/*
trait FooApp extends AskByPKsApp {
  def askByPKFactory: AskByPKFactory
  //
  lazy val barByPK = askByPKFactory.forClass(classOf[Bar])
  override def askByPKs = barByPK :: super.askByPKs
}
 */

/*
case class BarView(
  depHandlerFactory: DepHandlerFactory,
  barToFoo: DepAsk[Bar,Foo]
) extends DepHandlerProvider {
  def barToFooHandler(bar: Bar): Dep[Foo] = for {
    nodeValue <- nodeValueByPK.ask("123")
      ...
  } yield ...


  def handlers = Seq(
    depHandlerFactory.by(barToFoo)(bar => ctx => barToFooHandler(bar).resolve(ctx))

    depHandlerFactory.by(barToFoo)(barToFooHandler) //todo short
  )
}


def subView(a: Int): Dep[Int] = for {
    c ← askByPK(classOf[ValueNode], "123")
    b ← askFoo("B")
  } yield a + b + c.map(_.value).getOrElse(0)
 */






