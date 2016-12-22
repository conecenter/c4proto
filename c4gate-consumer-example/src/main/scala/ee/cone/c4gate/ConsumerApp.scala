
package ee.cone.c4gate

import java.util.UUID

import ee.cone.c4actor._
import ee.cone.c4gate.InternetProtocol._
import ee.cone.c4proto._

class TestConsumerApp extends ServerApp
  with QMessagesApp
  with TreeAssemblerApp
  with QReducerApp
  with KafkaApp
  with TxTransformsApp
  with SerialObserversApp
{
  def mainActorName = ActorName("http-test")
  def bootstrapServers: String = "localhost:9092"
  private lazy val testConsumerTxTransform = new TestConsumerTxTransform
  override def protocols: List[Protocol] = InternetProtocol :: super.protocols
  override def txTransforms: List[TxTransform] = testConsumerTxTransform :: super.txTransforms
}



class TestConsumerTxTransform extends TxTransform {
  def transform(tx: WorldTx): WorldTx = {
    val posts = By.srcId(classOf[HttpPost]).of(tx.world).values.flatten.toSeq
    val respEvents = posts.sortBy(_.time).flatMap { req ⇒
      val prev = new String(req.body.toByteArray, "UTF-8")
      val next = (prev.toLong * 3).toString
      val body = okio.ByteString.encodeUtf8(next)
      val resp = HttpPublication(req.path, Nil, body)
      LEvent.delete(req) :: LEvent.update(resp) :: Nil
    }
    val connections =
      By.srcId(classOf[TcpConnection]).of(tx.world).values.flatten.toSeq
    val size = s"${connections.size}\n"
    val sizeBody = okio.ByteString.encodeUtf8(size)
    println(size)
    val broadEvents = connections.flatMap { connection ⇒
      val key = UUID.randomUUID.toString
      LEvent.update(TcpWrite(key, connection.connectionKey, sizeBody, System.currentTimeMillis)) ::
      Nil
    }
    tx.add(respEvents: _*).add(broadEvents: _*)
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