
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
  val testMessageHandler = new MessageHandler(classOf[Group]) {
    def handleMessage(message: Group): Unit = {
      assert(group0==message)
      println("OK",message)
    }
  }
  val testActorName = ActorName("")
  val app = new QMessagesApp {
    def rawQSender: RawQSender =
      new RawQSender { def send(rec: QRecord): Unit = () }
    override def protocols: List[Protocol] = MyProtocol :: super.protocols
    def messageHandlers: List[MessageHandler[_]] = testMessageHandler :: Nil
  }
  val qMessageMapper =
    app.qMessageMapperFactory.create(testMessageHandler :: Nil)
  //
  val rec = app.qMessages.toRecord(LEvent.update(testActorName,"",group0))
  val mapping = new MessageMapping {
    def world = Map()
    def toSend = ???
    def add[M<:Product](out: LEvent[M]*): MessageMapping = ???
    def actorName: ActorName = testActorName
  }
  qMessageMapper.mapMessage(mapping, rec)
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
