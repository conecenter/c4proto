package ee.cone.c4http

import ee.cone.c4proto._
import org.apache.kafka.clients.producer.ProducerRecord

object ConsumerApp {
  def main(args: Array[String]): Unit = {
    try {
      val bootstrapServers = "localhost:9092"
      val pool = Pool()
      val producer = Producer(bootstrapServers)
      lazy val qRecords = QRecords(handlerLists){
        (k:Array[Byte],v:Array[Byte]) ⇒ producer.send(new ProducerRecord("http-gets", k, v)).get()
      }
      lazy val handlerLists: CoHandlerLists = CoHandlerLists(
        CoHandler(ProtocolKey)(QProtocol) ::
          CoHandler(ProtocolKey)(HttpProtocol) ::
          CoHandler(ReceiverKey)(new Receiver(classOf[HttpProtocol.RequestValue], {
            (req:HttpProtocol.RequestValue) ⇒
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
            qRecords.sendUpdate(resp.path,resp)
          })) ::
          Nil
      )

      val consumer = new ToIdempotentConsumer(bootstrapServers,"test-consumer","http-posts")(pool, { rec ⇒
        qRecords.receive(rec)
        println("received at: ",rec.offset)
      })
      consumer.start()
      while(consumer.state != Finished) {
        //println(consumer.state)
        Thread.sleep(1000)
      }
    } finally System.exit(0)
  }
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