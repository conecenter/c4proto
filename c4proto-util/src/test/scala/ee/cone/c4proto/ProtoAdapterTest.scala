
package ee.cone.c4proto

object MyApp extends App {
  import MyProtocol._
  val leader0 = Person("leader0", Some(40))
  val worker0 = Person("worker0", Some(30))
  val worker1 = Person("worker1", Some(20))
  val group0 = Group(Some(leader0), List(worker0,worker1))
  val handlerLists = CoHandlerLists(
    CoHandler(ProtocolKey)(MyProtocol) ::
    CoHandler(ReceiverKey)(new MessageMapper(classOf[Group], {
      (group1:Group) ⇒
      println(group0,group1,group0==group1)
    })) ::
    Nil
  )
  var rec: Option[QConsumerRecord] = None
  val qRecords = QRecords(handlerLists){ (k:Array[Byte],v:Array[Byte]) ⇒
    println(k.toList)
    println(v.toList)
    rec = Some(new QConsumerRecord {
      def key:Array[Byte] = k
      def value:Array[Byte] = v
      def offset = 0
    })
  }
  qRecords.sendUpdate("",group0)
  qRecords.receive(rec.get)
  //println(new String(bytes,"..."))
}

@protocol object MyProtocol extends Protocol {
  import BigDecimalProtocol._
  //com.squareup.wire.ProtoAdapter
  @Id(0x0003) case class Person(@Id(0x0007) name: String, @Id(0x0004) age: Option[BigDecimal] @scale(0))
  @Id(0x0001) case class Group(@Id(0x0005) leader: Option[Person], @Id(0x0006) worker: List[Person])
}
