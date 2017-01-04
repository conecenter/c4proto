
package ee.cone.c4actor

import ee.cone.c4assemble.Single
import ee.cone.c4proto.{Id, Protocol, protocol, scale}


object ProtoAdapterTest extends App {
  import MyProtocol._
  val leader0 = Person("leader0", Some(40), isActive = true)
  val worker0 = Person("worker0", Some(30), isActive = true)
  val worker1 = Person("worker1", Some(20), isActive = false)
  val group0 = Group("", Some(leader0), List(worker0,worker1))
  //
  val protocols: List[Protocol] = MyProtocol :: QProtocol :: Nil
  val rawQSender = new RawQSender { def send(rec: QRecord): Long = 0 }
  val qAdapterRegistry: QAdapterRegistry = QAdapterRegistry(protocols)
  val qMessages: QMessages = new QMessagesImpl(qAdapterRegistry, ()⇒rawQSender)
  //
  val lEvents = LEvent.update(group0)
  val updates = lEvents.map(qMessages.toUpdate)
  val recs = updates.map(qMessages.toRecord(NoTopicName,_))
  val world = qMessages.toTree(recs)
  val group1 = Single(By.srcId(classOf[Group]).of(world)(""))
  assert(group0==group1)
  println("OK",group1)
}

@protocol object MyProtocol extends Protocol {
  import ee.cone.c4proto.BigDecimalProtocol._
  //com.squareup.wire.ProtoAdapter
  @Id(0x0003) case class Person(
    @Id(0x0007) name: String,
    @Id(0x0004) age: Option[BigDecimal] @scale(0),
    @Id(0x0008) isActive: Boolean
  )
  @Id(0x0001) case class Group(
    @Id(0x0007) name: String,
    @Id(0x0005) leader: Option[Person],
    @Id(0x0006) worker: List[Person]
  )
}

