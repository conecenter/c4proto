
package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4proto.{Id, c4component, protocol}
import scala.collection.immutable.Seq

class ProtoAdapterTestApp extends ProtoAdapterTestAutoApp
  with ExecutableApp with VMExecutionApp
  with BaseApp with ProtoApp with BigDecimalApp with GzipRawCompressorApp

@c4component("ProtoAdapterTestAutoApp") class DefUpdateCompressionMinSize extends UpdateCompressionMinSize(0L)

@c4component("ProtoAdapterTestAutoApp")
class ProtoAdapterTest(qAdapterRegistry: QAdapterRegistry, toUpdate: ToUpdate, execution: Execution) extends Executable with LazyLogging {
  def run(): Unit = {
    import MyProtocol._
    val leader0 = D_Person("leader0", Some(40), isActive = true)
    val worker0 = D_Person("worker0", Some(30), isActive = true)
    val worker1 = D_Person("worker1", Some(20), isActive = false)
    val group0 = D_Group("", Some(leader0), List(worker0,worker1))
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
    execution.complete()
  }
}

@protocol("ProtoAdapterTestAutoApp") object MyProtocolBase   {
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
