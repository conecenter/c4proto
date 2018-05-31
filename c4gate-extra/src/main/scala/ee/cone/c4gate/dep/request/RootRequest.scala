package ee.cone.c4gate.dep.request

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep._
import ee.cone.c4actor.dep_impl.DepHandlersApp
import ee.cone.c4actor.{AssemblesApp, ProtocolsApp, QAdapterRegistry, WithPK}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4gate.AlienProtocol.FromAlienState
import ee.cone.c4gate.dep.request.RootRequestProtocol.RootRequest
import ee.cone.c4proto.{Id, Protocol, protocol}


trait RootDepApp
  extends DepHandlersApp
    with AssemblesApp
    with ProtocolsApp
    with DepAskFactoryApp
    with ContextIdInjectApp
    with DepOuterRequestFactoryApp {
  def rootDep: Dep[Any]

  private def rootAsk: DepAsk[RootRequest, Any] = depAskFactory.forClasses(classOf[RootRequest], classOf[Any])

  override def depHandlers: List[DepHandler] = rootAsk.by(_ ⇒ rootDep) :: inject[RootRequest](rootAsk, _.contextId) :: super.depHandlers

  override def assembles: List[Assemble] = new RootRequestCreator(qAdapterRegistry, depOuterRequestFactory) :: super.assembles

  override def protocols: List[Protocol] = RootRequestProtocol :: super.protocols

  def qAdapterRegistry: QAdapterRegistry
}

case class RootResponse(srcId: String, response: Option[_], sessionKey: String)

@assemble class RootRequestCreator(val qAdapterRegistry: QAdapterRegistry, u: DepOuterRequestFactory) extends Assemble {

  def SparkRootRequest(
    key: SrcId,
    alienTasks: Values[FromAlienState]
  ): Values[(SrcId, DepOuterRequest)] =
    for {
      alienTask ← alienTasks
    } yield {
      val rootRequest = RootRequest(alienTask.sessionKey)
      u.tupled(alienTask.sessionKey)(rootRequest)
    }

  def RootResponseGrabber(
    key: SrcId,
    responses: Values[DepResponse]
  ): Values[(SrcId, RootResponse)] =
    for {
      resp ← responses
      if resp.innerRequest.request.isInstanceOf[RootRequest]
    } yield {
      WithPK(RootResponse(resp.innerRequest.srcId, resp.value, resp.innerRequest.request.asInstanceOf[RootRequest].contextId))
    }
}

@protocol object RootRequestProtocol extends Protocol {

  @Id(0x0f32) case class RootRequest(
    @Id(0x0f33) contextId: String
  )

}