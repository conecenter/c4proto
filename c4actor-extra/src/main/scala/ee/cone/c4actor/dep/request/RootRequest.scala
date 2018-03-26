package ee.cone.c4actor.dep.request

import java.nio.ByteBuffer
import java.util.UUID

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.CtxType.ContextId
import ee.cone.c4actor.dep.request.RootRequestProtocol.RootRequest
import ee.cone.c4actor.dep._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by, was}
import ee.cone.c4gate.AlienProtocol.FromAlienState
import ee.cone.c4proto.{Id, Protocol, protocol}


trait RootDepApp extends RequestHandlerRegistryApp with AssemblesApp with ProtocolsApp {
  def rootDep: Dep[_]

  override def handlers: List[RequestHandler[_]] = RootRequestHandler(rootDep) :: super.handlers

  override def assembles: List[Assemble] = new RootRequestCreator :: super.assembles

  override def protocols: List[Protocol] = RootRequestProtocol :: super.protocols
}

case class RootRequestHandler(rootDep: Dep[_]) extends RequestHandler[RootRequest] {
  override def canHandle: Class[RootRequest] = classOf[RootRequest]

  override def handle: RootRequest => (Dep[_], ContextId) = request ⇒ (rootDep, request.contextId)
}

case class RootResponse(srcId: String, response: Option[_])

@assemble class RootRequestCreator extends Assemble {
  type ToResponse = SrcId

  def SparkRootRequest (
    key: SrcId,
    alienTasks: Values[FromAlienState]
  ): Values[(SrcId, DepRequestWithSrcId)] =
    for {
      alienTask ← alienTasks
    } yield {
      val rootRequest = RootRequest(alienTask.sessionKey)
      val srcId = RootRequestUtils.genPK(rootRequest)
      (srcId, DepRequestWithSrcId(srcId, rootRequest))
    }

  def RootResponseGrabber (
    key: SrcId,
    @by[ToResponse] responses: Values[DepResponse]
  ): Values[(SrcId, RootResponse)] =
    for {
      resp ← responses
      if resp.request.request.isInstanceOf[RootRequest]
    } yield {
      WithPK(RootResponse(resp.request.srcId, resp.value))
    }
}

@protocol object RootRequestProtocol extends Protocol {

  @Id(0x0f32) case class RootRequest(
    @Id(0x0f33) contextId: String
  )

}

object RootRequestUtils {

  def genPK(rq: RootRequest): SrcId = {
    val adapter = RootRequestProtocol.adapters.head
    val bytes = adapter.encode(rq)
    UUID.nameUUIDFromBytes(toBytes(adapter.id) ++ bytes).toString
  }

  private def toBytes(value: Long) =
    ByteBuffer.allocate(java.lang.Long.BYTES).putLong(value).array()
}