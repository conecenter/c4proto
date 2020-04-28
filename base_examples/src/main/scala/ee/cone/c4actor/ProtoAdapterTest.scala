
package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.ArgTypes.LazyOption

import ee.cone.c4proto.{Id, protocol}
import ee.cone.c4di._

import scala.collection.immutable.Seq

@c4("ProtoAdapterTestApp") final class ExampleUpdateCompressionMinSize extends UpdateCompressionMinSize(0L)

@c4("ProtoAdapterTestApp") final 
class ProtoAdapterTest(
  qAdapterRegistry: QAdapterRegistry, toUpdate: ToUpdate, execution: Execution,
  finTest: FinTest
) extends Executable with LazyLogging {
  import ee.cone.c4actor.MyProtocol._
  def simpleTest(): Unit = {
    val leader0 = D_Person("leader0", Some(40), isActive = true)
    val worker0 = D_Person("worker0", Some(30), isActive = true)
    val worker1 = D_Person("worker1", Some(20), isActive = false)
    val group0 = D_Group("", Some(leader0), List(worker0,worker1))
    //
    val lEvents = LEvent.update(group0)
    val updates = lEvents.map(toUpdate.toUpdate)
    val group1 = updates.map(update =>
      qAdapterRegistry.byId(update.valueTypeId).decode(update.value)
    ) match {
      case Seq(g:D_Group) => g
    }
    assert(group0==group1)
    logger.info(s"OK $group1")
  }
  def numTest(): Unit = {
    val model = D_BigDecimalContainer(88L,List(BigDecimal(7.5),BigDecimal(8)))
    val adapter = qAdapterRegistry.byName(model.getClass.getName)
    val encoded = adapter.encode(model)
    val decoded = adapter.decode(encoded)
    assert(model==decoded)
    logger.info(s"OK numTest")
  }
  def recursiveTest(): Unit = {
    val model = D_Branch(
      Option(D_Branch(Option(D_Leaf(1L)),Option(D_Leaf(2L)))),
      Option(D_Branch(Option(D_Leaf(3L)),Option(D_Leaf(4L))))
    )
    val adapter = qAdapterRegistry.byName(model.getClass.getName)
    val encoded = adapter.encode(model)
    val decoded = adapter.decode(encoded)
    assert(model==decoded)
    logger.info(s"OK recursiveTest")
  }
  def finTestTest(): Unit = {
    assert(finTest.get == "<Final>{NonFinal}</Final>")
    logger.info(s"OK finTestTest")
  }
  def run(): Unit = {
    simpleTest()
    numTest()
    recursiveTest()
    finTestTest()
    execution.complete()
  }
}

@protocol("ProtoAdapterTestApp") object MyProtocol {
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
  //
  @Id(0x0002) case class D_BigDecimalContainer(
    @Id(0x0003) l: Long,
    @Id(0x0004) b: List[BigDecimal]
  )
  //
  trait GTree
  @Id(0x0004) case class D_Branch(
    @Id(0x0002) left: LazyOption[GTree],
    @Id(0x0003) right: LazyOption[GTree]
  ) extends GTree
  @Id(0x0005) case class D_Leaf(
    @Id(0x0001) value: Long
  ) extends GTree

}

trait FinTest {
  def get: String
}
@c4("ProtoAdapterTestApp") final 
class NonFinalFinTest extends FinTest {
  def get: String = "{NonFinal}"
}
@c4("ProtoAdapterTestApp") final 
class FinalFinTest(inner: FinTest) extends FinTest {
  def get: String = s"<Final>${inner.get}</Final>"
}