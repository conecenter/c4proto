package ee.cone.c4http

import java.util.UUID

import ee.cone.c4http.TcpProtocol.Status
import ee.cone.c4proto._

class TestConsumerApp extends ServerApp
  with QMessagesApp
  with TreeAssemblerApp
  with ToIdempotentConsumerApp
  with ToStoredConsumerApp
  with KafkaProducerApp
{
  def messageMappers: List[MessageMapper[_]] =
    new PostMessageMapper(StreamKey("http-posts", "http-gate-state"),qMessages) ::
    new TcpStatusToStateMessageMapper(StreamKey("sse-status",stateTopic),qMessages) ::
    new TcpStatusToDisconnectMessageMapper(StreamKey("sse-status","sse-events"),qMessages) ::
    Nil
  def consumerGroupId: String = "http-test"
  def stateTopic = s"http-test-${UUID.randomUUID}-state"
  def statePartConsumerStreamKey: StreamKey = StreamKey(stateTopic,"")
  def bootstrapServers: String = "localhost:9092"
  override def protocols: List[Protocol] = ConnectionProtocol :: super.protocols
}

class PostMessageMapper(streamKey: StreamKey, qMessages: QMessages)
  extends MessageMapper(streamKey, classOf[HttpProtocol.RequestValue])
{
  def mapMessage(req: HttpProtocol.RequestValue): Seq[QProducerRecord] = {
    val next: String = try {
      val prev = new String(req.body.toByteArray, "UTF-8")
      (prev.toLong * 3).toString
    } catch {
      case r: Exception ⇒
        //throw new Exception("die")
        "###"
    }
    val body = okio.ByteString.encodeUtf8(next)
    val resp = HttpProtocol.RequestValue(req.path, Nil, body)
    qMessages.update(resp.path,resp) :: Nil
  }
}

class TcpStatusToStateMessageMapper(streamKey: StreamKey, qMessages: QMessages)
  extends MessageMapper(streamKey, classOf[TcpProtocol.Status])
{
  def mapMessage(message: Status): Seq[QProducerRecord] = {
    val srcId = message.connectionKey
    if(message.error.isEmpty)
      qMessages.update(srcId, ConnectionProtocol.Connection()) :: Nil
    else
      qMessages.delete(srcId, classOf[ConnectionProtocol.Connection]) :: Nil
  }
}

class TcpStatusToDisconnectMessageMapper(streamKey: StreamKey, qMessages: QMessages)
  extends MessageMapper(streamKey, classOf[TcpProtocol.Status])
{
  def mapMessage(message: Status): Seq[QProducerRecord] = {
    if(message.error.isEmpty) Nil
    else qMessages.update("", TcpProtocol.DisconnectEvent(message.connectionKey)) :: Nil
  }
}

@protocol object ConnectionProtocol extends Protocol {
  @Id(0x0003) case class Connection()
}

object ConsumerTest {
  def main(args: Array[String]): Unit = try {
    val app = new TestConsumerApp
    app.execution.run()
  } finally System.exit(0)
}


/*
object Test {

  class Change
  case class A(id: String, description: String)
  //case class B(id: String, description: String)

  case class World(aById: Map[String,A], aByDescription: Map[String,B])

  def keys(obj: A): Seq[(,)] =

  def reduce(world: World, next: A): World = {
    val prevOpt = world.aById.get(next.id)

  }


  update: Updated(Option(fromNode),Option(toNode))

  parse
  extract
  index
  join


  updatesA = crateUpdates(prevA, statesA)
  nextA = applyUpdates(prevA, updatesA)

  eventsA = makeEvents(prevA,prevB,nextB)




  class AAAIndexed(node: AAA, fromBBB: Seq[BBBKey], fromCCC: Seq[CCCKey])
  aaaIndex: Map[AAAKey,AAAIndexed]

  def mapDepBBBToAAA(bbb: BBB): Seq[AAAKey]
  def reduceBBBToAAA(aaa: AAA, bbb: BBB): AAA

////
  case class Update[V](from: Seq[V], to: Seq[V])

  trait MapReduce[Node, K, V, AV] {
    def map(node: Node): Map[K, V]
    def del(aggregateValue: AV, partialValue: V): AV
    def add(aggregateValue: AV, partialValue: V): AV
  }

  def reduce[K, V, AV](index: Map[K,AV], updates: Map[K,Update[V]]): Map[K,Update[AV]] = ???
  def map[K, V, AV](updates: Map[K,Update[AV]]): Map[K,Update[V]] = ???



}
*/
/*
object Test {
  case class Update[V](from: V, to: V)

  def add[V](a: V, b: V): V = ???
  def add[V](a: Update[V], b: Update[V]): Update[V] = ???


  A_diff = reduce(A_prev,B_diff)

  A_all = reduce(A_prev,A_diff)
  C_diff = map(A_diff)
  C_all = reduce(C_prev,C_diff)




}*/