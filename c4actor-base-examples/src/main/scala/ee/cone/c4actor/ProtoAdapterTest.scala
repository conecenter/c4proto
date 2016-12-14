
package ee.cone.c4actor

import ee.cone.c4actor.Types.World
import ee.cone.c4proto.{Id, Protocol, protocol, scale}

object ProtoAdapterTest extends App {
  import MyProtocol._
  val leader0 = Person("leader0", Some(40))
  val worker0 = Person("worker0", Some(30))
  val worker1 = Person("worker1", Some(20))
  val group0 = Group(Some(leader0), List(worker0,worker1))
  //
  val testMessageMapper = new MessageMapper(classOf[Group]) {
    def mapMessage(world: World, group1: Group): Seq[MessageMapResult] = {
      assert(group0==group1)
      println("OK",group1)
      Nil
    }
  }
  val testActorName = ActorName("")
  val app = new QMessagesApp {
    def rawQSender: RawQSender =
      new RawQSender { def send(rec: QRecord): Unit = () }
    override def protocols: List[Protocol] = MyProtocol :: super.protocols
    def messageMappers: List[MessageMapper[_]] = testMessageMapper :: Nil
  }
  val qMessageMapper =
    app.qMessageMapperFactory.create(testActorName, testMessageMapper :: Nil)
  //
  val rec = app.qMessages.toRecord(None, Send(testActorName,group0))
  qMessageMapper.mapMessage(Map(), rec)
}

@protocol object MyProtocol extends Protocol {
  import ee.cone.c4proto.BigDecimalProtocol._
  //com.squareup.wire.ProtoAdapter
  @Id(0x0003) case class Person(
    @Id(0x0007) name: String,
    @Id(0x0004) age: Option[BigDecimal] @scale(0)
  )
  @Id(0x0001) case class Group(
    @Id(0x0005) leader: Option[Person],
    @Id(0x0006) worker: List[Person]
  )
}
