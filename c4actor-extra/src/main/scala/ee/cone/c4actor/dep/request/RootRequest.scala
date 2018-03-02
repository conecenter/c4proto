package ee.cone.c4actor.dep.request

import java.nio.ByteBuffer
import java.util.UUID

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.CtxType.ContextId
import ee.cone.c4actor.dep.request.RootRequestProtocol.RootRequest
import ee.cone.c4actor.dep.{Dep, RequestHandler, RequestHandlerRegistryApp, RequestWithSrcId}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4gate.AlienProtocol.FromAlienState
import ee.cone.c4proto.{Id, Protocol, protocol}


trait RootDepApp extends RequestHandlerRegistryApp with AssemblesApp {
  def rootDep: Dep[_]

  override def handlers: List[RequestHandler[_]] = RootRequestHandler(rootDep) :: super.handlers

  override def assembles: List[Assemble] = new RootRequestCreator :: super.assembles
}

case class RootRequestHandler(rootDep: Dep[_]) extends RequestHandler[RootRequest] {
  override def canHandle: Class[RootRequest] = classOf[RootRequest]

  override def handle: RootRequest => (Dep[_], ContextId) = request ⇒ (rootDep, request.contextId)
}

@assemble class RootRequestCreator extends Assemble {
  def SparkRootRequest (
    key: SrcId,
    alienTasks: Values[FromAlienState]
  ): Values[(SrcId, RequestWithSrcId)] =
    for {
      alienTask ← alienTasks
    } yield {
      val rootRequest = RootRequest(alienTask.sessionKey)
      val srcId = RootRequestUtils.genPK(rootRequest)
      (srcId, RequestWithSrcId(srcId, rootRequest))
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