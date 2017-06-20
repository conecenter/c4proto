
package ee.cone.c4gate

import java.util.UUID

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4gate.HttpProtocol._
import ee.cone.c4gate.TcpProtocol._
import ee.cone.c4proto._
import ee.cone.c4actor.LEvent._
import ee.cone.c4actor.QProtocol.Offset
import ee.cone.c4assemble._
import ee.cone.c4assemble.Types.{Values, World}

class TestConsumerApp extends ServerApp
  with EnvConfigApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp with InitLocalsApp
  with UMLClientsApp
  with ManagementApp
{
  override def protocols: List[Protocol] = AlienProtocol :: HttpProtocol :: TcpProtocol :: super.protocols
  override def assembles: List[Assemble] = new TestAssemble :: super.assembles
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

@assemble class TestAssemble extends Assemble {
  def joinTestHttpPostHandler(
    key: SrcId,
    posts: Values[HttpPost]
  ): Values[(SrcId, TxTransform)] =
    for(post ← posts if post.path == "/abc")
      yield WithSrcId(TestHttpPostHandler(post.srcId,post))

  def needConsumer(
    key: SrcId,
    offsets: Values[Offset]
  ): Values[(SrcId,LocalPostConsumer)] =
    for(_ ← offsets) yield WithSrcId(LocalPostConsumer("/abc"))

  def joinDebug(
    key: SrcId,
    posts: Values[HttpPost]
  ): Values[(SrcId, TxTransform)] = {
    println(posts)
    Nil
  }

/*
  def joinAllTcpConnections(key: SrcId, items: Values[TcpConnection]): Values[(Unit, TcpConnection)] =
    items.map(()→_)
  def joinGateTester(key: Unit, connections: Values[TcpConnection]): Values[(SrcId, TxTransform)] =
    List("GateTester"→GateTester(connections))*/
}

case class TestHttpPostHandler(srcId: SrcId, post: HttpPost) extends TxTransform {
  def transform(local: World): World = {
    val resp = if(ErrorKey.of(local).nonEmpty) Nil else {
      val prev = new String(post.body.toByteArray, "UTF-8")
      val next = (prev.toLong * 3).toString
      val body = okio.ByteString.encodeUtf8(next)
      List(HttpPublication(post.path, Nil, body, Option(System.currentTimeMillis+4000)))
    }
    println(resp)
    add(delete[Product](post) ++ resp.flatMap(update[Product]))(local)
  }
}

/*
case object TestTimerKey extends WorldKey[java.lang.Long](0L)

case class GateTester(connections: Values[TcpConnection]) extends TxTransform {
  def transform(local: World): World = {
    val seconds = System.currentTimeMillis / 1000
    if(TestTimerKey.of(local) == seconds) return local
    val size = s"${connections.size}\n"
    val sizeBody = okio.ByteString.encodeUtf8(size)
    println(size)
    val broadEvents = connections.flatMap { connection ⇒
      val key = UUID.randomUUID.toString
      update(TcpWrite(key, connection.connectionKey, sizeBody, seconds))
    }
    add(broadEvents).andThen(TestTimerKey.set(seconds))(local)
  }
}
*/





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