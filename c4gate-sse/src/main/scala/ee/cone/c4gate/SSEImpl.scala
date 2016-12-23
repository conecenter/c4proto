package ee.cone.c4gate

import java.util.UUID

import ee.cone.c4actor.Types._
import ee.cone.c4actor._
import ee.cone.c4gate.InternetProtocol._

trait SSEApp extends TxTransformsApp with DataDependenciesApp {
  def sseAllowOrigin: Option[String]
  def indexFactory: IndexFactory
  //
  lazy val sseMessages = new SSEMessagesImpl(sseAllowOrigin)
  private lazy val sseTxTransform = new SSETxTransform(sseMessages)
  private lazy val httpPostByConnectionJoin = indexFactory.createJoinMapIndex(new HttpPostByConnectionJoin)
  private lazy val sseConnectionJoin = indexFactory.createJoinMapIndex(new SSEConnectionJoin)
  //
  override def txTransforms: List[TxTransform] = sseTxTransform :: super.txTransforms
  override def dataDependencies: List[DataDependencyTo[_]] =
    httpPostByConnectionJoin :: sseConnectionJoin :: super.dataDependencies
}

class SSEMessagesImpl(allowOriginOption: Option[String]) extends SSEMessages {
  def message(connectionKey: String, event: String, data: String, priority: Long): TcpWrite = {
    val header = if(priority > 0) "" else {
      val allowOrigin =
        allowOriginOption.map(v=>s"Access-Control-Allow-Origin: $v\n").getOrElse("")
      s"HTTP/1.1 200 OK\nContent-Type: text/event-stream\n$allowOrigin\n"
    }
    val escapedData = data.replaceAllLiterally("\n","\ndata: ")
    val str = s"${header}event: $event\ndata: $escapedData\n\n"
    val bytes = okio.ByteString.encodeUtf8(str)
    val key = UUID.randomUUID.toString
    TcpWrite(key,connectionKey,bytes,priority)
  }
}

class SSETxTransform(sseMessages: SSEMessages) extends TxTransform {
  def transform(tx: WorldTx): WorldTx = {
    val events = By.srcId(classOf[SSEConnection]).of(tx.world).values.flatten.toSeq.flatMap{ conn ⇒
      conn.state match{
        case None ⇒
          val time = System.currentTimeMillis()
          LEvent.update(ConnectionPingState(conn.connectionKey,time)) ::
            LEvent.update(sseMessages.message(conn.connectionKey,"connect",conn.connectionKey,0)) ::
            Nil
        case Some(state@ConnectionPingState(_,pingTime)) ⇒
          val time = System.currentTimeMillis()
          if(pingTime + 5000 > time) Nil else {
            val pongs =
              conn.posts.filter(post ⇒ post.headers.get("X-r-action").contains("pong"))
            if(pongs.nonEmpty)
              LEvent.update(ConnectionPingState(conn.connectionKey,time)) ::
                LEvent.update(sseMessages.message(conn.connectionKey,"ping",conn.connectionKey,1)) ::
                pongs.map(pong⇒LEvent.delete(pong.request))
            else
              LEvent.update(TcpDisconnect(conn.connectionKey)) ::
                LEvent.delete(state) ::
                Nil
          }
      }
    }
    tx.add(events:_*)
  }
}

/*
@protocol object SSEProtocol extends Protocol {

}
*/

case class SSEConnection(connectionKey: String, state: Option[ConnectionPingState], posts: List[HttpPostByConnection])
class SSEConnectionJoin extends Join3(
  By.srcId(classOf[TcpConnection]),
  By.srcId(classOf[ConnectionPingState]),
  By.srcId(classOf[HttpPostByConnection]),
  By.srcId(classOf[SSEConnection])
) {
  def join(
    tcpConnected: Values[TcpConnection],
    states: Values[ConnectionPingState],
    posts: Values[HttpPostByConnection]
  ) = tcpConnected.map(_.connectionKey).map(c ⇒
    c → SSEConnection(c, Single.option(states), posts)
  )
  def sort(nodes: Iterable[SSEConnection]) = Single.list(nodes.toList)
}

case class HttpPostByConnection(connectionKey: String, headers: Map[String,String], request: HttpPost)
class HttpPostByConnectionJoin extends Join1(
  By.srcId(classOf[HttpPost]),
  By.srcId(classOf[HttpPostByConnection])
){
  def join(
    posts: Values[HttpPost]
  ) = posts.flatMap( post ⇒
    if(post.path != "/connection") Nil else {
      val headers = post.headers.flatMap(h ⇒
        if(h.key.startsWith("X-r-")) Seq(h.key→h.value) else Nil
      ).toMap
      headers.get("X-r-connection").map(k ⇒ k → HttpPostByConnection(k,headers,post))
    }
  )
  def sort(nodes: Iterable[HttpPostByConnection]) = nodes.toList.sortBy(_.request.time)
}


// /connection X-r-connection -> q-add -> q-poll -> FromAlienDictMessage
// (0/1-1) ShowToAlien -> sendToAlien

//(World,Msg) => (WorldWithChanges,Seq[Send])