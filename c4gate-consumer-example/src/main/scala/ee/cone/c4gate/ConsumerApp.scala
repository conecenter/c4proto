
package ee.cone.c4gate

import java.time.Instant
import java.util.UUID

import ee.cone.c4actor.Types.{Index, SrcId, Values, World}
import ee.cone.c4actor._
import ee.cone.c4gate.InternetProtocol._
import ee.cone.c4proto._
import ee.cone.c4actor.LEvent._

class TestConsumerApp extends ServerApp
  with EnvConfigApp
  with QMessagesApp
  with TreeAssemblerApp
  with QReducerApp
  with KafkaProducerApp with KafkaConsumerApp
  with DataDependenciesApp
  with SerialObserversApp
{
  //"http-test-0" "localhost:9092"
  private lazy val testJoins =
    List(new TestHttpPostHandlerJoin, new AllTcpConnectionsJoin, new GateTesterJoin)
      .map(indexFactory.createJoinMapIndex(_))
  override def protocols: List[Protocol] = InternetProtocol :: super.protocols
  override def dataDependencies: List[DataDependencyTo[_]] =
    testJoins ::: super.dataDependencies
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



case class TestHttpPostHandler(srcId: SrcId, post: HttpPost) extends TxTransform {
  def transform(local: World): World = {
    val prev = new String(post.body.toByteArray, "UTF-8")
    val next = (prev.toLong * 3).toString
    val body = okio.ByteString.encodeUtf8(next)
    val resp = HttpPublication(post.path, Nil, body)
    add(Seq(delete(post), update(resp)))(local)
  }
}

class TestHttpPostHandlerJoin extends Join1(
  By.srcId(classOf[HttpPost]),
  By.srcId(classOf[TxTransform])
){
  private def withKey[P<:Product](c: P): Values[(SrcId,P)] =
    List(c.productElement(0).toString → c)
  def join(posts: Values[HttpPost]): Values[(SrcId, TxTransform)] =
    posts.map(post⇒TestHttpPostHandler(post.srcId,post)).flatMap(withKey)
  def sort(values: Iterable[TxTransform]): List[TxTransform] = Single.list(values.toList)
}

////

case object TestTimerKey extends WorldKey[java.lang.Long](0L)

case class TcpConnectionByUnit(noKey: String, connection: TcpConnection)

class AllTcpConnectionsJoin extends Join1(
  By.srcId(classOf[TcpConnection]),
  By.srcId(classOf[TcpConnectionByUnit])
){
  private def withKey[P<:Product](c: P): Values[(SrcId,P)] =
    List(c.productElement(0).toString → c)
  def join(items: Values[TcpConnection]): Values[(SrcId, TcpConnectionByUnit)] =
    items.map(TcpConnectionByUnit("",_)).flatMap(withKey)
  def sort(values: Iterable[TcpConnectionByUnit]): List[TcpConnectionByUnit] =
    values.toList.sortBy(_.connection.connectionKey)
}

class GateTesterJoin extends Join1(
  By.srcId(classOf[TcpConnectionByUnit]),
  By.srcId(classOf[TxTransform])
){
  private def withKey[P<:Product](c: P): Values[(SrcId,P)] =
    List(c.productElement(0).toString → c)
  def join(
    connections: Values[TcpConnectionByUnit]
  ): Values[(SrcId, TxTransform)] =
    withKey(GateTester("GateTester",connections.map(_.connection)))
  def sort(values: Iterable[TxTransform]): List[TxTransform] = Single.list(values.toList)
}

case class GateTester(id: String, connections: Values[TcpConnection]) extends TxTransform {
  def transform(local: World): World = {
    val seconds = System.currentTimeMillis / 1000
    if(TestTimerKey.of(local) == seconds) return local
    val size = s"${connections.size}\n"
    val sizeBody = okio.ByteString.encodeUtf8(size)
    println(size)
    val broadEvents = connections.flatMap { connection ⇒
      val key = UUID.randomUUID.toString
      Seq(update(TcpWrite(key, connection.connectionKey, sizeBody, seconds)))
    }
    Option(local)
      .map(add(broadEvents))
      .map(TestTimerKey.transform(_⇒seconds)).get
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