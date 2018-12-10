package ee.cone.c4ui.dep.request

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.ContextTypes.MockRoleOpt
import ee.cone.c4actor.dep.DepTypes.GroupId
import ee.cone.c4actor.dep._
import ee.cone.c4actor.dep_impl.{DepHandlersApp, DepResponseImpl}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4ui.dep.request.DepFilteredListRequestProtocol.FilteredListRequest
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4ui.FromAlienTask

case class FLRequestDef(listName: String, filterPK: String, matches: List[String] = List(".*"))(val requestDep: Dep[List[_]])

trait FilterListRequestApp {
  def filterDepList: List[FLRequestDef] = Nil
}

trait FilterListRequestHandlerApp
  extends DepHandlersApp
    with AssemblesApp
    with ProtocolsApp
    with FilterListRequestApp
    with DepAskFactoryApp
    with CommonIdInjectApps
    with DepRequestFactoryApp
    with PreHashingApp {

  private lazy val depMap: Map[(String, String), Dep[List[_]]] = filterDepList.map(elem ⇒ (elem.listName, elem.filterPK) → elem.requestDep).toMap

  private def fltAsk: DepAsk[FilteredListRequest, List[_]] = depAskFactory.forClasses(classOf[FilteredListRequest], classOf[List[_]])

  override def depHandlers: List[DepHandler] = fltAsk.by(rq ⇒ {
    depMap((rq.listName, rq.filterPK))
  }
  ) :: injectContext[FilteredListRequest](fltAsk, _.contextId) ::
    injectUser[FilteredListRequest](fltAsk, _.userId) ::
    injectMockRole[FilteredListRequest](fltAsk, rq ⇒ rq.mockRoleId.flatMap(id ⇒ rq.mockRoleEditable.map(ed ⇒ id → ed))) ::
    injectRole[FilteredListRequest](fltAsk, _.roleId) :: super.depHandlers

  override def assembles: List[Assemble] = new FilteredListResponseReceiver(preHashing) :: filterDepList.map(
    df ⇒ new FilterListRequestCreator(qAdapterRegistry, df.listName, df.filterPK, df.matches, depRequestFactory)
  ) ::: super.assembles

  override def protocols: List[Protocol] = DepFilteredListRequestProtocol :: super.protocols

  def qAdapterRegistry: QAdapterRegistry
}

case class FilteredListResponse(srcId: String, listName: String, filterPK: String, responseHashed: PreHashed[Option[_]]) extends LazyHashCodeProduct {
  lazy val response: Option[_] = responseHashed.value
}

object FilterListRequestCreatorUtils {
  def parseUrl(url: String): String = {
    val split = url.split("#").toList
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

import ee.cone.c4ui.dep.request.FilterListRequestCreatorUtils._


// TODO need to throw this into world
case class BranchWithUserId(branchId: String, contextId: String, userId: String, roleId: String, mockRole: MockRoleOpt)

@assemble class FilteredListResponseReceiver(
  preHashing: PreHashing
) extends Assemble {
  def FilterListResponseGrabber(
    key: SrcId,
    resp: Each[DepResponse]
  ): Values[(SrcId, FilteredListResponse)] =
    resp.innerRequest.request match {
      case request: FilteredListRequest ⇒
        val srcId = Murmur3Hash((request.branchId, request.listName, request.filterPK))
        resp match {
          case a: DepResponseImpl ⇒ List(WithPK(FilteredListResponse(srcId, request.listName, request.filterPK, a.valueHashed)))
          case _ ⇒ List(WithPK(FilteredListResponse(srcId, request.listName, request.filterPK, preHashing.wrap(resp.value))))
        }
      case _ ⇒ Nil
    }
}

@assemble class FilterListRequestCreator(
  val qAdapterRegistry: QAdapterRegistry,
  listName: String,
  filterPK: String,
  matches: List[String],
  u: DepRequestFactory
) extends Assemble {

  def SparkFilterListRequest(
    key: SrcId,
    alienTask: Each[FromAlienTask],
    sessionWithUser: Values[BranchWithUserId]
  ): Values[(GroupId, DepOuterRequest)] =
    if (matches.foldLeft(false)((z, regex) ⇒ z || parseUrl(alienTask.fromAlienState.location).matches(regex))) {
      val (userId, roleId, mockRole) =
        if (sessionWithUser.nonEmpty)
          sessionWithUser.head match {
            case p ⇒ (p.userId, p.roleId, p.mockRole)
          }
        else
          ("", "", None)
      val filterRequest = FilteredListRequest(alienTask.branchKey, alienTask.fromAlienState.sessionKey, userId, roleId, mockRole.map(_._1), mockRole.map(_._2), listName, filterPK)
      List(u.tupledOuterRequest(alienTask.branchKey)(filterRequest))
    } else Nil
}

@protocol(DepRequestCat) object DepFilteredListRequestProtocol extends Protocol {

  @Id(0x0a01) case class FilteredListRequest(
    @Id(0x0a0a) branchId: String,
    @Id(0x0a02) contextId: String,
    @Id(0x0a05) userId: String,
    @Id(0x0a06) roleId: String,
    @Id(0x0a08) mockRoleId: Option[String],
    @Id(0x0a09) mockRoleEditable: Option[Boolean],
    @Id(0x0a03) listName: String,
    @Id(0x0a07) filterPK: String
  )

}