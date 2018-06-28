package ee.cone.c4gate.dep.request

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.DepTypes.GroupId
import ee.cone.c4actor.dep._
import ee.cone.c4actor.dep_impl.DepHandlersApp
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4gate.AlienProtocol.FromAlienState
import ee.cone.c4gate.dep.request.DepFilteredListRequestProtocol.FilteredListRequest
import ee.cone.c4proto.{Id, Protocol, protocol}

case class FLRequestDef(listName: String, matches: List[String] = List(".*"))(val requestDep: Dep[List[_]])

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
    with DepOuterRequestFactoryApp
    with PreHashingApp {

  private lazy val depMap: Map[String, Dep[List[_]]] = filterDepList.map(elem ⇒ elem.listName → elem.requestDep).toMap

  private def fltAsk: DepAsk[FilteredListRequest, List[_]] = depAskFactory.forClasses(classOf[FilteredListRequest], classOf[List[_]])

  override def depHandlers: List[DepHandler] = fltAsk.by(rq ⇒ {
    depMap(rq.listName)
  }
  ) :: injectContext[FilteredListRequest](fltAsk, _.contextId) ::
    injectUser[FilteredListRequest](fltAsk, _.userId) ::
    injectRole[FilteredListRequest](fltAsk, _.roleId) :: super.depHandlers

  override def assembles: List[Assemble] = filterDepList.map(
    df ⇒ new FilterListRequestCreator(qAdapterRegistry, df.listName, df.matches, depOuterRequestFactory, preHashing)
  ) ::: super.assembles

  override def protocols: List[Protocol] = DepFilteredListRequestProtocol :: super.protocols

  def qAdapterRegistry: QAdapterRegistry
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

import ee.cone.c4gate.dep.request.FilterListRequestCreatorUtils._


// TODO need to throw this into world
case class SessionWithUserId(contextId: String, userId: String, roleId: String)

@assemble class FilterListRequestCreator(
  val qAdapterRegistry: QAdapterRegistry,
  listName: String,
  matches: List[String],
  u: DepOuterRequestFactory,
  preHashing: PreHashing
) extends Assemble {

  def SparkFilterListRequest(
    key: SrcId,
    alienTasks: Values[FromAlienState],
    sessionWithUser: Values[SessionWithUserId]
  ): Values[(GroupId, DepOuterRequest)] =
    for {
      alienTask ← alienTasks
      if matches.foldLeft(false)((z, regex) ⇒ z || parseUrl(alienTask.location).matches(regex))
    } yield {
      val (userId, roleId) =
        if (sessionWithUser.nonEmpty)
          sessionWithUser.head match {
            case p ⇒ (p.userId, p.roleId)
          }
        else
          ("", "")
      val filterRequest = FilteredListRequest(alienTask.sessionKey, userId, roleId, listName)
      u.tupled(alienTask.sessionKey)(filterRequest)
    }

  def FilterListResponseGrabber(
    key: SrcId,
    responses: Values[DepResponse]
  ): Values[(SrcId, FilteredListResponse)] =
    for {
      resp ← responses
      if resp.innerRequest.request.isInstanceOf[FilteredListRequest] && resp.innerRequest.request.asInstanceOf[FilteredListRequest].listName == listName
    } yield {
      WithPK(FilteredListResponse(resp.innerRequest.srcId, listName, resp.innerRequest.request.asInstanceOf[FilteredListRequest].contextId, preHashing.wrap(resp.value)))
    }
}

@protocol object DepFilteredListRequestProtocol extends Protocol {

  @Id(0x0a01) case class FilteredListRequest(
    @Id(0x0a02) contextId: String,
    @Id(0x0a05) userId: String,
    @Id(0x0a06) roleId: String,
    @Id(0x0a03) listName: String
  )

}