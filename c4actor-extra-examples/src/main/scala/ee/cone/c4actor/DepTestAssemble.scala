package ee.cone.c4actor

import ee.cone.c4actor.DepTestProtocol.{DepTestRequest, Spark}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.DepTypes.{DepCtx, DepRequest, GroupId}
import ee.cone.c4actor.dep._
import ee.cone.c4actor.dep.request.ContextIdRequestProtocol.ContextIdRequest
import ee.cone.c4actor.dep_impl.DepHandlersApp
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4proto.{Id, Protocol, protocol}

trait DepTestAssemble
  extends AssemblesApp
    with DepHandlersApp
    with ProtocolsApp
    with DepOuterRequestFactoryApp
    with DepAskFactoryApp
    with ContextIdInjectApp {
  def testDep: Dep[Any]

  def testContextId: String = "LUL"

  def qAdapterRegistry: QAdapterRegistry


  override def protocols: List[Protocol] = DepTestProtocol :: super.protocols

  private val testRequestAsk = depAskFactory.forClasses(classOf[DepTestRequest], classOf[Any])

  override def depHandlers: List[DepHandler] = testRequestAsk.by(_ ⇒ testDep) :: inject[DepTestRequest](testRequestAsk, _ ⇒ testContextId) :: super.depHandlers

  override def assembles: List[Assemble] = new DepTestAssembles(qAdapterRegistry, depOuterRequestFactory) :: super.assembles
}

@protocol object DepTestProtocol extends Protocol {

  @Id(0x0455) case class DepTestRequest()

  @Id(0x0567) case class Spark(
    @Id(0x1337) srcId: String
  )

}

case class DepTestHandler(dep: Dep[_], contextId: String) extends DepHandler {
  def className: String = classOf[DepTestRequest].getName

  def handle: DepRequest ⇒ DepCtx ⇒ Resolvable[_] = _ ⇒ ctx ⇒ dep.resolve(ctx + (ContextIdRequest() →  contextId))
}

case class DepTestResponse(srcId: String, response: Option[_])

@assemble class DepTestAssembles(val qAdapterRegistry: QAdapterRegistry, f: DepOuterRequestFactory) extends Assemble {
  def GiveBirth(
    firstBornId: SrcId,
    sparks: Values[Spark]
  ): Values[(GroupId, DepOuterRequest)] =
    for {
      spark ← sparks
    } yield {
      f.tupled("test")(DepTestRequest())
    }

  def HarvestBirth(
    responseId: SrcId,
    responses: Values[DepResponse]
  ): Values[(SrcId, DepTestResponse)] =
    for {
      resp ← responses
      if resp.innerRequest.request.isInstanceOf[DepTestRequest]
    } yield {
      WithPK(DepTestResponse(resp.innerRequest.srcId, resp.value))
    }
}
