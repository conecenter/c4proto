package ee.cone.c4gate.dep.request

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.DepTypeContainer.ContextId
import ee.cone.c4actor.{AssemblesApp, ProtocolsApp, QAdapterRegistry, WithPK}
import ee.cone.c4actor.dep._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4gate.AlienProtocol.FromAlienState
import ee.cone.c4gate.dep.request.DepFilteredListRequestProtocol.FilteredListRequest
import ee.cone.c4proto.{Id, Protocol, protocol}

case class FLRequestDef(listName: String, requestDep: Dep[_])

trait FilterListRequestApp {
  def filterDepList: List[FLRequestDef] = Nil
}

trait FilterListRequestHandlerApp extends RequestHandlersApp with AssemblesApp with ProtocolsApp with FilterListRequestApp {

  override def handlers: List[RequestHandler[_]] = FilteredListRequestHandler(filterDepList) :: super.handlers

  override def assembles: List[Assemble] = filterDepList.map(df ⇒ new FilterListRequestCreator(qAdapterRegistry, df.listName)) ::: super.assembles

  override def protocols: List[Protocol] = DepFilteredListRequestProtocol :: super.protocols

  def qAdapterRegistry: QAdapterRegistry
}

case class FilteredListRequestHandler(flr: List[FLRequestDef]) extends RequestHandler[FilteredListRequest] {
  def canHandle: Class[FilteredListRequest] = classOf[FilteredListRequest]

  private lazy val depMap: Map[String, Dep[_]] = flr.map(elem ⇒ elem.listName → elem.requestDep).toMap

  def handle: FilteredListRequest => (Dep[_], ContextId) = request ⇒ (depMap(request.listName), request.contextId)
}

case class FilteredListResponse(srcId: String, listName: String, sessionKey: String, response: Option[_])

@assemble class FilterListRequestCreator(val qAdapterRegistry: QAdapterRegistry, listName: String) extends Assemble with DepAssembleUtilityImpl {

  def SparkFilterListRequest(
    key: SrcId,
    alienTasks: Values[FromAlienState]
  ): Values[(SrcId, DepOuterRequest)] =
    for {
      alienTask ← alienTasks
    } yield {
      val filterRequest = FilteredListRequest(alienTask.sessionKey, listName)
      WithPK(generateDepOuterRequest(filterRequest, alienTask.sessionKey))
    }

  def FilterListResponseGrabber(
    key: SrcId,
    responses: Values[DepOuterResponse]
  ): Values[(SrcId, FilteredListResponse)] =
    for {
      resp ← responses
      if resp.request.innerRequest.request.isInstanceOf[FilteredListRequest] && resp.request.innerRequest.request.asInstanceOf[FilteredListRequest].listName == listName
    } yield {
      WithPK(FilteredListResponse(resp.request.srcId, listName, resp.request.innerRequest.request.asInstanceOf[FilteredListRequest].contextId, resp.value))
    }
}

@protocol object DepFilteredListRequestProtocol extends Protocol {

  @Id(0x0a01) case class FilteredListRequest(
    @Id(0x0a02) contextId: String,
    @Id(0x0a03) listName: String
  )

}