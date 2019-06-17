package ee.cone.c4actor

import ee.cone.c4actor.DepTestProtocol.{D_DepTestRequest, D_Spark}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.DepTypes.{DepCtx, DepRequest, GroupId}
import ee.cone.c4actor.dep._
import ee.cone.c4actor.dep.request.ContextIdRequestProtocol.N_ContextIdRequest
import ee.cone.c4actor.dep_impl.DepHandlersApp
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4proto.{Id, Protocol, protocol}

trait DepTestAssemble
  extends AssemblesApp
    with DepHandlersApp
    with ProtocolsApp
    with DepRequestFactoryApp
    with DepAskFactoryApp
    with CommonIdInjectApps {
  def testDep: Dep[Any]

  def testContextId: String = "LUL"

  def qAdapterRegistry: QAdapterRegistry


  override def protocols: List[Protocol] = DepTestProtocol :: super.protocols

  private lazy val testRequestAsk = depAskFactory.forClasses(classOf[D_DepTestRequest], classOf[Any])

  override def depHandlers: List[DepHandler] = testRequestAsk.by(_ ⇒ testDep) :: injectRole[D_DepTestRequest](testRequestAsk, _ ⇒ testContextId) :: super.depHandlers

  override def assembles: List[Assemble] = new DepTestAssembles(qAdapterRegistry, depRequestFactory) :: super.assembles
}

@protocol(TestCat) object DepTestProtocolBase   {

  @Id(0x0455) case class D_DepTestRequest()

  @Id(0x0567) case class D_Spark(
    @Id(0x1337) srcId: String
  )

}

case class DepTestHandler(dep: Dep[_], contextId: String) extends DepHandler {
  def requestClassName: String = classOf[D_DepTestRequest].getName

  def handle: DepRequest ⇒ DepCtx ⇒ Resolvable[_] = _ ⇒ ctx ⇒ dep.resolve(ctx + (N_ContextIdRequest() →  contextId))
}

case class DepTestResponse(srcId: String, response: Option[_])

@assemble class DepTestAssemblesBase(val qAdapterRegistry: QAdapterRegistry, f: DepRequestFactory)   {
  def GiveBirth(
    firstBornId: SrcId,
    spark: Each[D_Spark]
  ): Values[(GroupId, DepOuterRequest)] =
    List(f.tupledOuterRequest("test")(D_DepTestRequest()))

  def HarvestBirth(
    responseId: SrcId,
    resp: Each[DepResponse]
  ): Values[(SrcId, DepTestResponse)] =
    if(resp.innerRequest.request.isInstanceOf[D_DepTestRequest])
      List(WithPK(DepTestResponse(resp.innerRequest.srcId, resp.value)))
    else Nil
}
