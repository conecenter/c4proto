
package ee.cone.c4proto

import ee.cone.c4proto.Types.World

object ProtoAdapterTest extends App {
  import MyProtocol._
  val leader0 = Person("leader0", Some(40))
  val worker0 = Person("worker0", Some(30))
  val worker1 = Person("worker1", Some(20))
  val group0 = Group(Some(leader0), List(worker0,worker1))

  val testActorName = ActorName("")
  val testMessageMapper = new MessageMapper(classOf[Group]) {
    def actorName: ActorName = testActorName
    def mapMessage(world: World, group1: Group): Seq[MessageMapResult] = {
      assert(group0==group1)
      println("OK",group1)
      Nil
    }
  }
  val app = new QMessagesApp {
    override def protocols: List[Protocol] = MyProtocol :: super.protocols
    def messageMappers: List[MessageMapper[_]] = testMessageMapper :: Nil
  }
  val rec = app.qMessages.toRecord(testStreamKey, ""→group0)
  app.qMessageMapper.mapMessage(testStreamKey, rec)
}

@protocol object MyProtocol extends Protocol {
  import BigDecimalProtocol._
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
