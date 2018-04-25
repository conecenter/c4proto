package ee.cone.c4gate.dep.request

import java.nio.ByteBuffer
import java.util.UUID

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.DepTypeContainer.ContextId
import ee.cone.c4actor.dep._
import ee.cone.c4actor.{AssemblesApp, ProtocolsApp, QAdapterRegistry, WithPK}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4gate.AlienProtocol.FromAlienState
import ee.cone.c4gate.dep.request.RootRequestProtocol.RootRequest
import ee.cone.c4proto.{Id, Protocol, protocol}


trait RootDepApp extends RequestHandlersApp with AssemblesApp with ProtocolsApp {
  def rootDep: Dep[_]

  override def handlers: List[RequestHandler[_]] = RootRequestHandler(rootDep) :: super.handlers

  override def assembles: List[Assemble] = new RootRequestCreator(qAdapterRegistry) :: super.assembles

  override def protocols: List[Protocol] = RootRequestProtocol :: super.protocols

  def qAdapterRegistry: QAdapterRegistry
}

case class RootRequestHandler(rootDep: Dep[_]) extends RequestHandler[RootRequest] {
  override def canHandle: Class[RootRequest] = classOf[RootRequest]

  override def handle: RootRequest => (Dep[_], ContextId) = request ⇒ (rootDep, request.contextId)
}

case class RootResponse(srcId: String, response: Option[_], sessionKey: String)

@assemble class RootRequestCreator(val qAdapterRegistry: QAdapterRegistry) extends Assemble with DepAssembleUtilityImpl {

  def SparkRootRequest(
    key: SrcId,
    alienTasks: Values[FromAlienState]
  ): Values[(SrcId, DepOuterRequest)] =
    for {
      alienTask ← alienTasks
    } yield {
      val rootRequest = RootRequest(alienTask.sessionKey)
      WithPK(generateDepOuterRequest(rootRequest, alienTask.sessionKey))
    }

  def RootResponseGrabber(
    key: SrcId,
    responses: Values[DepOuterResponse]
  ): Values[(SrcId, RootResponse)] =
    for {
      resp ← responses
      if resp.request.innerRequest.request.isInstanceOf[RootRequest]
    } yield {
      WithPK(RootResponse(resp.request.srcId, resp.value, resp.request.innerRequest.request.asInstanceOf[RootRequest].contextId))
    }
}

@protocol object RootRequestProtocol extends Protocol {

  @Id(0x0f32) case class RootRequest(
    @Id(0x0f33) contextId: String
  )

}