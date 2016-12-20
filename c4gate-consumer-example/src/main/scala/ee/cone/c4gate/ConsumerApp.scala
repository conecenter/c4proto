
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

  private lazy val postMessageHandler = new PostMessageHandler(gateActorName)
  private lazy val worldProvider: WorldProvider with Executable =
    actorFactory.create(appActorName, messageHandlers)
  private lazy val tcpEventBroadcaster =
    new TcpEventBroadcaster(appActorName,gateActorName)(()⇒worldProvider.world, qMessages)


  override def toStart: List[Executable] =
    worldProvider :: tcpEventBroadcaster :: super.toStart
  override def protocols: List[Protocol] = InternetProtocol :: super.protocols
  def messageHandlers: List[MessageHandler[_]] =
    postMessageHandler :: Nil
}

class PostMessageHandler(gateActorName: ActorName) extends MessageHandler(classOf[HttpRequestValue]){
  def handleMessage(req: HttpRequestValue): Unit = {
    val prev = new String(req.body.toByteArray, "UTF-8")
    val next = (prev.toLong * 3).toString
    val body = okio.ByteString.encodeUtf8(next)
    val resp = HttpRequestValue(req.path, Nil, body)
    //res.add(LEvent.update(gateActorName, req.path, resp))
  }
}

class TcpEventBroadcaster(appActorName: ActorName, gateActorName: ActorName)(
    getWorld: ()⇒World, qMessages: QMessages
) extends Executable {
  def run(executionContext: ExecutionContext): Unit = {
    qMessages.send(LEvent.update(gateActorName, appActorName.value, ForwardingConf(appActorName.value, List(
      ForwardingRule("/"),
      ForwardingRule(":sse")
    ))))
    Iterator.iterate(Map():Index[SrcId, HttpRequestValue]){ prevRequests ⇒
      val connections: Index[SrcId, TcpConnected] =
        By.srcId(classOf[TcpConnected]).of(getWorld())
      val size = s"${connections.size}\n"
      val sizeBody = okio.ByteString.encodeUtf8(size)
      println(size)
      connections.keys.foreach{ key ⇒
        qMessages.send(LEvent.update(gateActorName, key, TcpWrite(key,sizeBody)))
      }
      ////
      val requests: Index[SrcId, HttpRequestValue] =
         By.srcId(classOf[HttpRequestValue]).of(getWorld())
      //requests.foreach{ case }

      requests
    }.foreach(_⇒ Thread.sleep(3000))
    //qMessages.send(LEvent.delete(actorName, key, classOf[TcpConnected]))
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