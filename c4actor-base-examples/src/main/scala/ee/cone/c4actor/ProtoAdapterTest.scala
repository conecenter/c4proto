
package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4proto.{Id, Protocol, protocol}


object ProtoAdapterTest extends App with LazyLogging {
  import MyProtocol._
  val leader0 = D_Person("leader0", Some(40), isActive = true)
  val worker0 = D_Person("worker0", Some(30), isActive = true)
  val worker1 = D_Person("worker1", Some(20), isActive = false)
  val group0 = D_Group("", Some(leader0), List(worker0,worker1))
  //
  val protocols: List[Protocol] = MyProtocol :: QProtocol :: Nil
  val qAdapterRegistry: QAdapterRegistry = QAdapterRegistryFactory(protocols)
  val toUpdate: ToUpdate = new ToUpdateImpl(qAdapterRegistry, DeCompressorRegistryImpl(Nil)(), Option(GzipFullCompressor()), 0L)()()
  //
  val lEvents = LEvent.update(group0)
  val updates = lEvents.map(toUpdate.toUpdate)
  val group1 = updates.map(update ⇒
    qAdapterRegistry.byId(update.valueTypeId).decode(update.value)
  ) match {
    case Seq(g:D_Group) ⇒ g
  }
  assert(group0==group1)
  logger.info(s"OK $group1")
}

@protocol(TestCat) object MyProtocolBase   {
  import ee.cone.c4proto.BigDecimalProtocol._

  //com.squareup.wire.ProtoAdapter
  @Id(0x0003) case class D_Person(
    @Id(0x0007) name: String,
    @Id(0x0004) age: Option[BigDecimal],
    @Id(0x0008) isActive: Boolean
  )
  @Id(0x0001) case class D_Group(
    @Id(0x0007) name: String,
    @Id(0x0005) leader: Option[D_Person],
    @Id(0x0006) worker: List[D_Person]
  )
}
