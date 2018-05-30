package ee.cone.c4gate.dep.request

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.DepTypes.ContextId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.ContextTypes.ContextId
import ee.cone.c4actor.dep._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4gate.AlienProtocol.FromAlienState
import ee.cone.c4gate.dep.request.DepFilteredListRequestProtocol.FilteredListRequest
import ee.cone.c4proto.{Id, Protocol, protocol}

case class FLRequestDef(listName: String, requestDep: Dep[_], matches: List[String] = List(".*"))

trait FilterListRequestApp {
  def filterDepList: List[FLRequestDef] = Nil
}

trait FilterListRequestHandlerApp extends RequestHandlersApp with AssemblesApp with ProtocolsApp with FilterListRequestApp with PreHashingApp {

  override def handlers: List[RequestHandler[_]] = FilteredListRequestHandler(filterDepList) :: super.handlers

  override def assembles: List[Assemble] = filterDepList.map(
    df ⇒ new FilterListRequestCreator(qAdapterRegistry, df.listName, df.matches, preHashing)
  ) ::: super.assembles

  override def protocols: List[Protocol] = DepFilteredListRequestProtocol :: super.protocols

  def qAdapterRegistry: QAdapterRegistry
}

case class FilteredListRequestHandler(flr: List[FLRequestDef]) extends RequestHandler[FilteredListRequest] {
  def canHandle: Class[FilteredListRequest] = classOf[FilteredListRequest]

  private lazy val depMap: Map[String, Dep[_]] = flr.map(elem ⇒ elem.listName → elem.requestDep).toMap

  def handle: FilteredListRequest => (Dep[_], ContextId) = request ⇒ (depMap(request.listName), request.contextId)
}

case class FilteredListResponse(srcId: String, listName: String, sessionKey: String, responseHashed: PreHashed[Option[_]]) extends LazyHashCodeProduct {
  lazy val response: Option[_] = responseHashed.value
}

object FilterListRequestCreatorUtils {
  def parseUrl(url: String): String = {
    val split = url.split("c4.html#").toList
    try {
      if (split.size == 1) {
        ""
      } else {
        split.tail.head
      }
    } catch {
      case _: Exception ⇒ ""
    }
  }
}

import FilterListRequestCreatorUtils._

@assemble class FilterListRequestCreator(
  val qAdapterRegistry: QAdapterRegistry,
  listName: String,
  matches: List[String],
  preHashing: PreHashing
) extends Assemble with DepAssembleUtilityImpl {

  def SparkFilterListRequest(
    key: SrcId,
    alienTasks: Values[FromAlienState]
  ): Values[(SrcId, DepOuterRequest)] =
    for {
      alienTask ← alienTasks
      if matches.foldLeft(false)((z, regex) ⇒ z || parseUrl(alienTask.location).matches(regex))
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
      WithPK(FilteredListResponse(resp.request.srcId, listName, resp.request.innerRequest.request.asInstanceOf[FilteredListRequest].contextId, preHashing.wrap(resp.value)))
    }
}

@protocol object DepFilteredListRequestProtocol extends Protocol {

  @Id(0x0a01) case class FilteredListRequest(
    @Id(0x0a02) contextId: String,
    @Id(0x0a03) listName: String
  )

}