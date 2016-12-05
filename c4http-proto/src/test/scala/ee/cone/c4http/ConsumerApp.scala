package ee.cone.c4http

import java.util.UUID

import ee.cone.c4http.ConnectionProtocol.Connection
import ee.cone.c4http.TcpProtocol.Status
import ee.cone.c4proto.Types.{Index, SrcId}
import ee.cone.c4proto._

class TestConsumerApp extends ServerApp
  with QMessagesApp
  with TreeAssemblerApp
  with ToIdempotentConsumerApp
  with ToStoredConsumerApp
  with KafkaProducerApp
{
  def messageMappers: List[MessageMapper[_]] =
    new PostMessageMapper(StreamKey("http-posts", "http-gate-state")) ::
    new TcpStatusToStateMessageMapper(StreamKey("sse-status",stateTopic)) ::
    new TcpStatusToDisconnectMessageMapper(StreamKey("sse-status","sse-events")) ::
    Nil
  def consumerGroupId: String = "http-test"
  def stateTopic = s"http-test-${UUID.randomUUID}-state"
  def statePartConsumerStreamKey: StreamKey = StreamKey(stateTopic,"")
  def bootstrapServers: String = "localhost:9092"
  override def protocols: List[Protocol] = ConnectionProtocol :: super.protocols
}

class PostMessageMapper(val streamKey: StreamKey)
  extends MessageMapper(classOf[HttpProtocol.RequestValue])
{
  def mapMessage(req: HttpProtocol.RequestValue): Seq[(SrcId,HttpProtocol.RequestValue)] = {
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
    Seq(resp.path→resp)
  }
}

class TcpEventBroadcaster(
    worldProvider: WorldProvider, //
    qMessages: QMessages, streamKey: StreamKey, rawQSender: RawQSender
) extends Runnable {
  override def run(): Unit = {
    while(true){
      val world = worldProvider.world
      val worldKey = By.srcId(classOf[ConnectionProtocol.Connection])
      val connections: Index[SrcId, Connection] = worldKey.of(world)
      val sizeBody = okio.ByteString.encodeUtf8(connections.size.toString)
      connections.keys.foreach{ connectionKey ⇒
        val message = TcpProtocol.WriteEvent(connectionKey,sizeBody)
        rawQSender.send(qMessages.toRecord(streamKey, message))
      }
      Thread.sleep(3000)
    }
  }
}

class TcpStatusToStateMessageMapper(val streamKey: StreamKey)
  extends MessageMapper(classOf[TcpProtocol.Status])
{
  def mapMessage(message: Status): Seq[Product] = {
    val srcId = message.connectionKey
    if(message.error.isEmpty) Seq(srcId → ConnectionProtocol.Connection())
    else Seq(srcId → classOf[ConnectionProtocol.Connection])
  }
}

class TcpStatusToDisconnectMessageMapper(val streamKey: StreamKey)
  extends MessageMapper(classOf[TcpProtocol.Status])
{
  def mapMessage(message: Status): Seq[TcpProtocol.DisconnectEvent] = {
    if(message.error.isEmpty) Nil
    else Seq(TcpProtocol.DisconnectEvent(message.connectionKey))
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