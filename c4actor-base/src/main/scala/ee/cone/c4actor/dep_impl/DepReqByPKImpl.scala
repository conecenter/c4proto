package ee.cone.c4actor.dep_impl

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.WithPK
import ee.cone.c4actor.dep._
import ee.cone.c4actor.dep_impl.ByPKRequestProtocol.N_ByPKRequest
import ee.cone.c4actor.dep_impl.ByPKTypes.ByPkItemSrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, Single, assemble, by}
import ee.cone.c4proto.{Id, protocol}

@protocol("ByPKRequestHandlerAutoApp") object ByPKRequestProtocolBase   {
  @Id(0x0070) case class N_ByPKRequest(
    @Id(0x0071) className: String,
    @Id(0x0072) itemSrcId: String
  )
}

case class InnerByPKRequest(request: DepInnerRequest, className: String)

object ByPKTypes {
  type ByPkItemSrcId = SrcId
}

@assemble class ByPKAssembleBase   {
  def byPKRequestWithSrcToItemSrcId(
    key: SrcId,
    rq: Each[DepInnerRequest]
  ): Values[(ByPkItemSrcId, InnerByPKRequest)] =
    if(!rq.request.isInstanceOf[N_ByPKRequest]) Nil else {
      val brq = rq.request.asInstanceOf[N_ByPKRequest]
      List(brq.itemSrcId -> InnerByPKRequest(rq, brq.className))
    }
}

@assemble class ByPKGenericAssembleBase[A <: Product](handledClass: Class[A], util: DepResponseFactory)   {
  def BPKRequestToResponse(
    key: SrcId,
    @by[ByPkItemSrcId] rq: Each[InnerByPKRequest],
    items: Values[A]
  ): Values[(SrcId, DepResponse)] =
    if(rq.className != handledClass.getName) Nil
    else List(WithPK(util.wrap(rq.request, Option(items.toList))))
}

object ByPKAssembles {
  def apply(askByPKs: List[AbstractAskByPK]): List[Assemble] =
    new ByPKAssemble :: askByPKs.distinct.collect{ case bc: AskByPKImpl[_] => bc.assemble }
}

case class AskByPKFactoryImpl(depAskFactory: DepAskFactory, util: DepResponseFactory) extends AskByPKFactory {
  def forClass[A<:Product](cl: Class[A]): AskByPK[A] =
    AskByPKImpl(cl.getName, util)(cl,depAskFactory.forClasses(classOf[N_ByPKRequest],classOf[List[A]]))
}
case class AskByPKImpl[A<:Product](name: String, util: DepResponseFactory)(
  theClass: Class[A], depAsk: DepAsk[N_ByPKRequest,List[A]]
) extends AskByPK[A] {
  def list(id: SrcId): Dep[List[A]] = depAsk.ask(N_ByPKRequest(name,id))
  def option(id: SrcId): Dep[Option[A]] = list(id).map(Single.option)
  def assemble: Assemble = new ByPKGenericAssemble(theClass, util)
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
    c <- askByPK(classOf[D_ValueNode], "123")
    b <- askFoo("B")
  } yield a + b + c.map(_.value).getOrElse(0)
 */






