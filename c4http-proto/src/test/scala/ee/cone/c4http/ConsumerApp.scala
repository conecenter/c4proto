package ee.cone.c4http

import ee.cone.c4proto._

object ConsumerApp {
  def main(args: Array[String]): Unit = {
    try {
      val bootstrapServers = "localhost:9092"
      val pool = Pool()
      val findAdapter = new FindAdapter(Seq(KafkaProtocol,HttpProtocol))()
      val producer = Producer(bootstrapServers)
      val toSrcId = new Handling[String](findAdapter)
        .add(classOf[HttpProtocol.RequestValue])((r:HttpProtocol.RequestValue)⇒r.path)
      val sender: Sender = new Sender(producer, "http-gets", findAdapter, toSrcId)
      val reduce = new Handling[Unit](findAdapter)
        .add(classOf[HttpProtocol.RequestValue]) {
          (req: HttpProtocol.RequestValue) ⇒
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
            sender.send(resp)
        }
      val receiver = new Receiver(findAdapter, reduce)
      val consumer = new ToIdempotentConsumer(bootstrapServers,"test-consumer","http-posts")(pool, { rec ⇒
        receiver.receive(rec)
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

}
*/