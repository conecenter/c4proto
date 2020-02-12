
package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4gate.HttpProtocol._
import ee.cone.c4proto._
import ee.cone.c4actor.LEvent._
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4assemble._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4gate.HttpProtocol.{N_Header, S_HttpResponse}

/*
tmp/kafka_2.11-0.10.1.0/bin/kafka-simple-consumer-shell.sh --broker-list localhost:9092 --topic inbox
tmp/kafka_2.11-0.10.1.0/bin/kafka-topics.sh  --zookeeper localhost:2181 --describe
tmp/kafka_2.11-0.10.1.0/bin/kafka-topics.sh  --zookeeper localhost:2181 --delete --topic inbox
?tmp/kafka_2.11-0.10.1.0/bin/kafka-consumer-offset-checker.sh --zookeeper localhost:2181  --topic inbox --group http-test
?ConsumerGroupCommand
tmp/kafka_2.11-0.10.1.0/bin/kafka-configs.sh --zookeeper localhost:2181 --describe --entity-type topics --entity-name inbox

...kafka-console-consumer.sh --key-deserializer

curl 127.0.0.1:8067/connection -v -H x-r-action:pong -H x-r-connection:...
*/

@c4assemble("TestConsumerApp") class ConsumerTestAssembleBase(catchNonFatal: CatchNonFatal, publisher: Publisher)   {
  def joinTestHttpHandler(
    key: SrcId,
    req: Each[S_HttpRequest]
  ): Values[(SrcId, TxTransform)] =
    if(req.path == "/abc" || req.path.startsWith("/abd/"))
      List(WithPK(TestHttpHandler(req.srcId,req)(catchNonFatal,publisher))) else Nil

  def needConsumer(
    key: SrcId,
    first: Each[S_Firstborn]
  ): Values[(SrcId,LocalHttpConsumer)] =
    List(LocalHttpConsumer("/abc"),LocalHttpConsumer("/abd/*")).map(WithPK(_))

  def joinDebug(
    key: SrcId,
    posts: Values[S_HttpRequest]
  ): Values[(SrcId, TxTransform)] = {
    //println(posts)
    Nil
  }

/*
  def joinAllTcpConnections(key: SrcId, items: Values[S_TcpConnection]): Values[(Unit, S_TcpConnection)] =
    items.map(()->_)
  def joinGateTester(key: Unit, connections: Values[S_TcpConnection]): Values[(SrcId, TxTransform)] =
    List("GateTester"->GateTester(connections))*/
}

case class TestHttpHandler(srcId: SrcId, req: S_HttpRequest)(catchNonFatal: CatchNonFatal, publisher: Publisher) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = catchNonFatal {
    val next = if(req.method == "POST"){
      val prev = req.body.utf8()
      (prev.toLong * 3).toString
    } else s"GET-${req.path} ${Math.random()}"
    val body = okio.ByteString.encodeUtf8(next)
    val now = System.currentTimeMillis
    val resp =
      S_HttpResponse(req.srcId,200,List(N_Header("content-type","text/html; charset=UTF-8")),ToByteString(s"sync $next ${now-req.time}\n"),now)
    val pub = publisher.publish(ByPathHttpPublication(req.path, Nil, ToByteString(s"async $next\n")), 4000)
    logger.info(s"$resp --- $pub")
    TxAdd(delete(req) ++ update(resp) ++ pub)(local)
  }("test"){ e =>
    TxAdd(delete(req))(local)
  }
}

/*
case object TestTimerKey extends WorldKey[java.lang.Long](0L)

case class GateTester(connections: Values[S_TcpConnection]) extends TxTransform {
  def transform(local: World): World = {
    val seconds = System.currentTimeMillis / 1000
    if(TestTimerKey.of(local) == seconds) return local
    val size = s"${connections.size}\n"
    val sizeBody = okio.ByteString.encodeUtf8(size)
    println(size)
    val broadEvents = connections.flatMap { connection =>
      val key = UUID.randomUUID.toString
      update(S_TcpWrite(key, connection.connectionKey, sizeBody, seconds))
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

  trait MapReduce[D_Node, K, V, AV] {
    def map(node: D_Node): Map[K, V]
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