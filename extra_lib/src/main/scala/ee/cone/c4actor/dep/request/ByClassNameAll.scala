package ee.cone.c4actor.dep.request

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep._
import ee.cone.c4actor.dep.request.ByClassNameAllRequestProtocol.N_ByClassNameAllRequest
import ee.cone.c4actor.{AssembleName, AssemblesApp, WithPK}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4di.{Component, ComponentsApp, c4, provide}
import ee.cone.c4proto.{Id, protocol}

@c4("ByClassNameRequestMix") final class ByClassNameAllAskImpl(depFactory: DepFactory) extends ByClassNameAllAsk {
  def askByClAll[A <: Product](cl: Class[A]): Dep[List[A]] = depFactory.uncheckedRequestDep[List[A]](N_ByClassNameAllRequest(cl.getName))
}

trait ByClassNameAllAsk {
  def askByClAll[A <: Product](cl: Class[A]): Dep[List[A]]
}

trait ByClassNameRequestMixBase extends ByClassNameRequestApp

trait GeneralByClNameAllClass {
  def value: Class[_ <: Product]
}
class ByClNameAllClass[CL <: Product](val value: Class[CL]) extends GeneralByClNameAllClass

trait ByClassNameRequestApp extends ComponentsApp {
  import ee.cone.c4actor.ComponentProvider.provide
  private lazy val byClNameAllClassesComponent = provide(classOf[GeneralByClNameAllClass], ()=>byClNameAllClasses.map(new ByClNameAllClass(_)))
  override def components: List[Component] = byClNameAllClassesComponent :: super.components
  def byClNameAllClasses: List[Class[_ <: Product]] = Nil
}

trait ByClassNameAllRequestHandlerAppBase extends ByClassNameRequestApp

@c4("ByClassNameAllRequestHandlerApp") final class ByClassNameAllRequestHandlerAssembles(
  byClNameAllClasses: List[GeneralByClNameAllClass],
  byClassNameAllRequestGenericHandlerFactory: ByClassNameAllRequestGenericHandlerFactory
) {
  @provide def assembles: Seq[Assemble] =
    byClNameAllClasses.map(_.value)
      .map(cl => cl.getName -> cl).groupBy(_._1).values.map(_.head._2).toList
      .map(cl => byClassNameAllRequestGenericHandlerFactory.create(cl))
}

@c4multiAssemble("ByClassNameAllRequestHandlerApp") class ByClassNameAllRequestGenericHandlerBase[Model <: Product](modelCl: Class[Model])(
  util: DepResponseFactory
) extends AssembleName("ByClassNameAllRequestGenericHandler", modelCl) {
  type ByClassNameRequestAll = AbstractAll

  def GatherAllModels(
    modelId: SrcId,
    model: Each[Model]
  ): Values[(ByClassNameRequestAll, Model)] = List(All -> model)

  def HandleRequest(
    requestId: SrcId,
    rq: Each[DepInnerRequest],
    @byEq[ByClassNameRequestAll](All) models: Values[Model]
  ): Values[(SrcId, DepResponse)] =
    rq.request match {
      case request: N_ByClassNameAllRequest if request.className == modelCl.getName =>
        List(WithPK(util.wrap(rq, Option(models.toList))))
      case _ => Nil
    }
}

@protocol("ByClassNameAllRequestHandlerApp") object ByClassNameAllRequestProtocol {

  @Id(0x0230) case class N_ByClassNameAllRequest(
    @Id(0x0231) className: String
  )

}
