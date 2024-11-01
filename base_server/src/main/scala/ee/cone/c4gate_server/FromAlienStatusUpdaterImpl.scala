package ee.cone.c4gate_server

import ee.cone.c4actor.Types.LEvents
import ee.cone.c4actor.{Early, Executable, Execution, LEvent}
import ee.cone.c4di.c4
import ee.cone.c4gate.HttpProtocol.{N_Header, S_HttpRequest}
import okio.ByteString

import java.util.UUID
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.annotation.tailrec

object FromAlienStatusUpdaterImpl {
  case class State(logKey: String, pongedAt: Long, savedAt: Long)
}
@c4("AbstractHttpGatewayApp") final class FromAlienStatusUpdaterImpl(
  worldProvider: WorldProvider, execution: Execution, factory: FromAlienStatusFactory,
) extends FromAlienStatusUpdater with Executable with Early {
  import FromAlienStatusUpdaterImpl._
  private val queue = new LinkedBlockingQueue[(String,String)]
  def pong(logKey: String, value: String): Unit = queue.put((logKey, value))
  def run(): Unit = iteration(Map.empty, 0L)
  @tailrec private def iteration(wasLogStates: Map[String,State], checkedAt: Long): Unit = {
    val messageOpt = Option(queue.poll(1, TimeUnit.SECONDS))
    val now = System.currentTimeMillis
    val logStates = messageOpt match {
      case Some((logKey, value)) if value contains "L" =>
        val wasLogState = wasLogStates.getOrElse(logKey, State(logKey, 0L, 0L))
        wasLogStates.updated(logKey, wasLogState.copy(pongedAt = now))
      case None => wasLogStates
    }
    if(now - checkedAt < 1000) iteration(logStates, checkedAt) else {
      val res: Seq[(Option[State],LEvents)] = logStates.values.toSeq.sortBy(_.logKey).map{ logState =>
        def req(isOnline: Boolean): LEvents = factory.create(logState.logKey, isOnline)
        if(now - logState.pongedAt > 5000) (None, req(false))
        else if(now - logState.savedAt > 60000) (Option(logState.copy(savedAt = now)), req(true))
        else (Option(logState), Nil)
      }
      val willLogStates = (for((stOpt,_)<-res; st<-stOpt) yield st.logKey->st).toMap
      val lEvents = for((_,evs)<-res; ev<-evs) yield ev
      execution.aWait(worldProvider.tx(_=>Left(lEvents.toList))(_))
      iteration(willLogStates, now)
    }
  }
}

trait FromAlienStatusFactory {
  def create(logKey: String, isOnline: Boolean): LEvents
}

@c4("AbstractHttpGatewayApp") final class FromAlienStatusFactoryImpl extends FromAlienStatusFactory {
  def create(logKey: String, isOnline: Boolean): LEvents = {
    val now = System.currentTimeMillis
    val headers = List(N_Header("x-r-op", "online"), N_Header("x-r-branch", logKey))
    val body = ByteString.encodeUtf8(if(isOnline) "1" else "")
    LEvent.update(S_HttpRequest(UUID.randomUUID.toString, "POST", "/connection", None, headers, body, now))
  }
}
