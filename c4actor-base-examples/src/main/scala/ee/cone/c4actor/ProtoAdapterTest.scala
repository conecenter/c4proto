
package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.World
import ee.cone.c4proto.{Id, Protocol, protocol, scale}


object ProtoAdapterTest extends App {
  import MyProtocol._
  val leader0 = Person("leader0", Some(40))
  val worker0 = Person("worker0", Some(30))
  val worker1 = Person("worker1", Some(20))
  val group0 = Group("", Some(leader0), List(worker0,worker1))
  //
  val app = new QMessagesApp {
    def rawQSender: RawQSender =
      new RawQSender { def send(rec: QRecord): Long = 0 }
    override def protocols: List[Protocol] = MyProtocol :: super.protocols
  }
  class MyMapping(val world: World, val toSend: Seq[Update]) extends WorldTx {
    def add[M<:Product](out: LEvent[M]*): WorldTx = {
      val ups = out.map(msg⇒app.qMessages.toUpdate(msg))
      new MyMapping(app.qMessages.toTree(ups.map(u⇒app.qMessages.toRecord(NoTopicName,u))),ups)
    }
  }
  //
  val world = new MyMapping(Map(),Nil).add(LEvent.update(group0)).world
  val group1 = Single(By.srcId(classOf[Group]).of(world)(""))
  assert(group0==group1)
  println("OK",group1)
}

@protocol object MyProtocol extends Protocol {
  import ee.cone.c4proto.BigDecimalProtocol._
  //com.squareup.wire.ProtoAdapter
  @Id(0x0003) case class Person(
    @Id(0x0007) name: String,
    @Id(0x0004) age: Option[BigDecimal] @scale(0)
  )
  @Id(0x0001) case class Group(
    @Id(0x0007) name: String,
    @Id(0x0005) leader: Option[Person],
    @Id(0x0006) worker: List[Person]
  )
}

