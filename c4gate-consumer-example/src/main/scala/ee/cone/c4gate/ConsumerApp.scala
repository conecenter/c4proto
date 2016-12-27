
package ee.cone.c4gate

import java.util.UUID

import ee.cone.c4actor._
import ee.cone.c4gate.InternetProtocol._
import ee.cone.c4gate.TestClockProtocol.ClockData
import ee.cone.c4proto._

class TestConsumerApp extends ServerApp
  with EnvConfigApp
  with QMessagesApp
  with TreeAssemblerApp
  with QReducerApp
  with KafkaProducerApp with KafkaConsumerApp
  with TxTransformsApp
  with SerialObserversApp
{
  //"http-test-0" "localhost:9092"
  private lazy val testConsumerTxTransform = new TestConsumerTxTransform
  override def protocols: List[Protocol] = TestClockProtocol :: InternetProtocol :: super.protocols
  override def txTransforms: List[TxTransform] = testConsumerTxTransform :: super.txTransforms
}

/*
tmp/kafka_2.11-0.10.1.0/bin/kafka-simple-consumer-shell.sh --broker-list localhost:9092 --topic inbox
tmp/kafka_2.11-0.10.1.0/bin/kafka-topics.sh  --zookeeper localhost:2181 --describe
tmp/kafka_2.11-0.10.1.0/bin/kafka-topics.sh  --zookeeper localhost:2181 --delete --topic inbox
?tmp/kafka_2.11-0.10.1.0/bin/kafka-consumer-offset-checker.sh --zookeeper localhost:2181  --topic inbox --group http-test
?ConsumerGroupCommand
tmp/kafka_2.11-0.10.1.0/bin/kafka-configs.sh --zookeeper localhost:2181 --describe --entity-type topics --entity-name inbox

...kafka-console-consumer.sh --key-deserializer

curl 127.0.0.1:8067/connection -v -H X-r-action:pong -H X-r-connection:...
*/



@protocol object TestClockProtocol extends Protocol {
  @Id(0x0001) case class ClockData(@Id(0x0002) key: String, @Id(0x0003) seconds: Long)
}

class TestConsumerTxTransform extends TxTransform {
  def transform(tx: WorldTx): WorldTx = {
    val seconds = System.currentTimeMillis / 1000
    if(tx.get(classOf[ClockData]).getOrElse("",Nil).exists(_.seconds==seconds)) return tx

    val clockEvent = LEvent.update(ClockData("",seconds))
    val posts = tx.get(classOf[HttpPost]).values.flatten
    val respEvents = posts.toSeq.sortBy(_.time).flatMap { req ⇒
      val prev = new String(req.body.toByteArray, "UTF-8")
      val next = (prev.toLong * 3).toString
      val body = okio.ByteString.encodeUtf8(next)
      val resp = HttpPublication(req.path, Nil, body)
      LEvent.delete(req) :: LEvent.update(resp) :: Nil
    }
    val connections = tx.get(classOf[TcpConnection]).values.flatten
    val size = s"${connections.size}\n"
    val sizeBody = okio.ByteString.encodeUtf8(size)
    println(size)
    val broadEvents = connections.flatMap { connection ⇒
      val key = UUID.randomUUID.toString
      LEvent.update(TcpWrite(key, connection.connectionKey, sizeBody, seconds)) ::
      Nil
    }
    tx.add(Seq(clockEvent) ++ respEvents ++ broadEvents)
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