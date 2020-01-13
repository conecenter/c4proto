package ee.cone.c4actor.dep.request

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.{DepResponse, _}
import ee.cone.c4actor.dep.request.ByClassNameRequestProtocol.N_ByClassNameRequest
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble, by, was}
import ee.cone.c4proto.{Id, protocol}

trait ByClassNameRequestHandlerAppBase extends AssemblesApp with SerializationUtilsApp with DepResponseFactoryApp {
  def byClassNameClasses: List[Class[_ <: Product]] = Nil
  def idGenUtil: IdGenUtil

  override def assembles: List[Assemble] = byClassNameClasses.map(className => new ByClassNameGenericAssemble(className, idGenUtil.srcIdFromSrcIds(className.getName), depResponseFactory)) ::: super.assembles
}

case class InnerByClassNameRequest(request: DepInnerRequest, className: String, from: Int, to: Int)

@assemble class ByClassNameGenericAssembleBase[A <: Product](handledClass: Class[A], classSrcId: SrcId, depResponseFactory: DepResponseFactory)
  extends AssembleName("ByClassNameGenericAssemble", handledClass) with ByClassNameRequestUtils {
  type ByCNSrcId = SrcId
  type ByCNRqSrcId = SrcId

  def BCNItemsOnSrcId(
    key: SrcId,
    item: Each[A]
  ): Values[(ByCNSrcId, A)] = List((classSrcId + "ByCN") -> item)


  def BCNRequestToClassSrcId(
    key: SrcId,
    rq: Each[DepInnerRequest]
  ): Values[(ByCNRqSrcId, DepInnerRequest)] =
    rq.request match {
      case request: N_ByClassNameRequest if request.className == handledClass.getName => List((classSrcId + "ByCN") -> rq)
      case _ => Nil
    }


  def BCNRequestToResponse(
    key: SrcId,
    @by[ByCNRqSrcId] rq: Each[DepInnerRequest],
    @by[ByCNSrcId] items: Values[A]
  ): Values[(SrcId, DepResponse)] =
    rq.request match {
      case byCNRq: N_ByClassNameRequest if byCNRq.className == handledClass.getName =>
        List(WithPK(depResponseFactory.wrap(rq, Option(takeWithDefaultParams(items.toList)(byCNRq.from)(byCNRq.count)))))
      case _ => Nil
    }

}

@protocol("ByClassNameRequestHandlerApp") object ByClassNameRequestProtocolBase {

  @Id(0x0f26) case class N_ByClassNameRequest(
    @Id(0x0f27) className: String,
    @Id(0x0f28) from: Int,
    @Id(0x0f29) count: Int
  )

}

trait ByClassNameRequestUtils {
  private def customTake[A]: List[A] => Int => List[A] = list => count => if (count < 0) list else list.take(count)

  def takeWithDefaultParams[A]: List[A] => Int => Int => List[A] = list => from => count => customTake(list.drop(from))(count)
}