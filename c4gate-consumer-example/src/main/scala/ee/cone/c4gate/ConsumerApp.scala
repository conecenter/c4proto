
package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4gate.InternetProtocol._
import ee.cone.c4actor.Types.{Index, SrcId, World}
import ee.cone.c4proto._

class TestConsumerApp extends ServerApp
  with QMessagesApp
  with TreeAssemblerApp
  with QReducerApp
  with KafkaApp
{
  private def appActorName = ActorName("http-test")
  private def gateActorName = ActorName("http-gate")
  def bootstrapServers: String = "localhost:9092"

  private lazy val postMessageMapper = new PostMessageMapper(gateActorName)
  private lazy val worldProvider: WorldProvider with Executable =
    actorFactory.create(appActorName, messageMappers)
  private lazy val tcpEventBroadcaster =
    new TcpEventBroadcaster(appActorName,gateActorName)(()⇒worldProvider.world, qMessages)


  override def toStart: List[Executable] =
    worldProvider :: tcpEventBroadcaster :: super.toStart
  override def protocols: List[Protocol] = InternetProtocol :: super.protocols
  def messageMappers: List[MessageMapper[_]] =
    postMessageMapper :: TcpStatusMapper :: Nil
}

class PostMessageMapper(gateActorName: ActorName) extends MessageMapper(classOf[HttpRequestValue]){
  def mapMessage(res: MessageMapping, req: HttpRequestValue): MessageMapping = {
    val prev = new String(req.body.toByteArray, "UTF-8")
    val next = (prev.toLong * 3).toString
    val body = okio.ByteString.encodeUtf8(next)
    val resp = HttpRequestValue(req.path, Nil, body)
    res.add(Send(gateActorName,resp))
  }
}

class TcpEventBroadcaster(appActorName: ActorName, gateActorName: ActorName)(
    getWorld: ()⇒World, qMessages: QMessages
) extends Executable {
  def run(executionContext: ExecutionContext): Unit = {
    qMessages.send(Send(gateActorName, ForwardingConf(appActorName.value, List(
      ForwardingRule("/"),
      ForwardingRule(":sse")
    ))))
    while(true){
      val connections: Index[SrcId, TcpStatus] =
        By.srcId(classOf[TcpStatus]).of(getWorld())
      val size = s"${connections.size}\n"
      val sizeBody = okio.ByteString.encodeUtf8(size)
      println(size)
      connections.values.flatten.foreach{ connection ⇒
        val message = TcpWrite(connection.connectionKey,sizeBody)
        qMessages.send(Send(gateActorName, message))
      }
      Thread.sleep(3000)
    }
  }
}

object TcpStatusMapper extends MessageMapper(classOf[TcpStatus]){
  def mapMessage(res: MessageMapping, message: TcpStatus): MessageMapping = {
    val srcId = message.connectionKey
    res.add(
      if(message.error.isEmpty) Update(srcId, TcpStatus(srcId,""))
      else Delete(srcId, classOf[TcpStatus])
    )
  }
}

object ConsumerTest extends Main((new TestConsumerApp).execution.run)



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