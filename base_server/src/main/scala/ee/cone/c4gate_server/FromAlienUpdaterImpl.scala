package ee.cone.c4gate_server

import ee.cone.c4actor.Types.{LEvents, NextOffset}
import ee.cone.c4actor.{Early, Executable, Execution, GetByPK, LEvent, RichContext, WithPK, WorldSource}
import ee.cone.c4assemble.ToPrimaryKey
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4gate.AlienProtocol.N_FromAlienWish
import ee.cone.c4gate.{FromAlienWishUtil, SessionUtil}
import ee.cone.c4gate_server.FromAlienUpdateManager.{FromAlienWishes, IsOnline}

import java.util.concurrent.LinkedBlockingQueue
import scala.annotation.tailrec
import scala.concurrent.Promise
import scala.util.Success

@c4("AbstractHttpGatewayApp") final class FromAlienUpdaterFactoryImpl(
  factory: FromAlienUpdaterImplFactory
) extends FromAlienUpdaterFactory {
  def create(logKey: String): FromAlienUpdater = factory.create(logKey)
}

@c4multi("AbstractHttpGatewayApp") final class FromAlienUpdaterImpl(logKey: String)(
  man: FromAlienUpdateManager, execution: Execution,
) extends FromAlienUpdater {
  private val sessionKeyP = Promise[String]()
  def send(value: String): Unit = if(value.nonEmpty){
    val (sessionKey, values, isMain) = parseWishes(value)
    if(isMain && !sessionKeyP.isCompleted) execution.success(sessionKeyP, sessionKey)
    if(isMain) man.send(IsOnline(sessionKey, value = true))
    man.send(FromAlienWishes(logKey, sessionKey, values))
  }
  def stop(): Unit = sessionKeyP.future.value.foreach{
    case Success(sessionKey) => man.send(IsOnline(sessionKey, value = false))
  }
  def parseWishes(value: String): (String, List[N_FromAlienWish], Boolean) = {
    val m = parsePairs(value).toMap
    val sessionKey = m("x-r-session")
    val values = parsePairs(m.getOrElse("x-r-patches","")).map{ case (k,v) => N_FromAlienWish(k.toLong,v) }.toList
    val isMain = m.get("x-r-is-main").exists(_.nonEmpty)
    (sessionKey, values, isMain)
  }
  def parsePairs(value: String): Seq[(String,String)] = (value match {
    case "" => Nil case v if v.startsWith("-") => v.substring(1).split("\n-").map(_.replace("\n ","\n")).toSeq
  }).grouped(2).map{ case Seq(k,v) => k->v }.toSeq
}

object FromAlienUpdateManager {
  sealed trait Message extends Product
  case class IsOnline(sessionKey: String, value: Boolean) extends Message
  case class FromAlienWishes(branchKey: String, sessionKey: String, values: List[N_FromAlienWish]) extends Message
}
// updater do not check if tx ignored, client will resend freq-ly
@c4("AbstractHttpGatewayApp") final class FromAlienUpdateManager(
  worldSource: WorldSource, txSend: TxSend, sessionUtil: SessionUtil, fromAlienWishUtil: FromAlienWishUtil,
) extends Executable with Early {
  import FromAlienUpdateManager._
  def send(message: Message): Unit = queue.put(Right(message))
  private val queue = new LinkedBlockingQueue[Either[RichContext, Message]]
  def run(): Unit = worldSource.doWith(queue, ()=>iteration(Right(None),Nil))
  @tailrec private def iteration(
    worldOpt: Either[RichContext,Option[NextOffset]], targets: List[Message]
  ): Unit = worldOpt match {
    case Left(world) if targets.nonEmpty =>
      val lEvents = targets.distinctBy(p=>(p.getClass.getName,ToPrimaryKey(p))).flatMap{
        case p: IsOnline => sessionUtil.trySetStatus(world, p.sessionKey, p.value)
        case p: FromAlienWishes => fromAlienWishUtil.addWishes(world, p.branchKey, p.sessionKey, p.values)
      }
      if(lEvents.nonEmpty) iteration(Right(Option(txSend.send(world, lEvents))), Nil) else iteration(worldOpt, Nil)
    case _ => queue.take() match {
      case Right(p) => iteration(worldOpt, p :: targets)
      case Left(w) => worldOpt match {
        case Right(readAfterWriteOffsetOpt) if !worldSource.ready(w, readAfterWriteOffsetOpt) =>
          iteration(worldOpt, targets)
        case _ => iteration(Left(w), targets)
      }
    }
  }
}






/*
  ws:
  check log/session exists
  dos protection with backpressure (from SelfDosProtectionHttpHandler)
  die if x-r-auth; work with reqId on react level
  saving handler

  http statuses do not affect socket -- keep delivery!

  session do not need consumer
*/

/*
@c4("AbstractHttpGatewayApp") final class SelfDosProtectionHttpHandler(
  httpResponseFactory: RHttpResponseFactory,
  getHttpRequestCount: GetByPK[HttpRequestCount],
) extends LazyLogging {
  val sessionWaitingRequests = 8
  def wire: RHttpHandlerCreate = next => (request,local) =>
    if((for{
      sessionKey <- ReqGroup.session(request)
      count <- getHttpRequestCount.ofA(local).get(sessionKey) if count.count > sessionWaitingRequests
    } yield true).nonEmpty){
      logger.warn(s"429 ${request.path}")
      logger.debug(s"429 ${request.path} ${request.headers}")
      httpResponseFactory.directResponse(request,_.copy(status=429)) // Too Many Requests
    } else next(request,local)
}*/
/*
  def aliveBySession(
    key: SrcId,
    @distinct @by[Alive] request: Each[S_HttpRequest]
  ): Values[(ASessionKey, S_HttpRequest)] =
    ReqGroup.session(request).map(_->request).toList

  def count(
    key: SrcId,
    @by[ASessionKey] requests: Values[S_HttpRequest]
  ): Values[(SrcId, HttpRequestCount)] =
    WithPK(HttpRequestCount(key,requests.size)) :: Nil*/
case class HttpRequestCount(sessionKey: SrcId, count: Long)

/*
+wishlist send
+  online/offline
+protect by sessionKey
+wishlist recv gate
wishlist recv main
wishlist purge
  redraw

FromAlienStatus life?
*/

/*



 */