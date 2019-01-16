package ee.cone.tests

import java.io.ByteArrayInputStream

import ee.cone.c4actor.{QAdapterRegistry, QAdapterRegistryFactory}
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.dbrequest._
import ee.cone.tests.TestDbOrig.{OtherOrig, OtherOrig2, TestOrig}
import scalikejdbc._


object DBTest {
  def main(args: Array[String]): Unit = {
    dbTest
    val registry: QAdapterRegistry = QAdapterRegistryFactory.apply(TestDbOrig :: Nil)
    val adapter: OrigDBAdapter = OracleOrigDBAdapter(registry)

    val factory = OracleOrigSchemaBuilderFactory(registry)
    val builder = factory.make(classOf[TestOrig], Nil)
    val testOrig = TestOrig("1", 2, 3,
      Some("4"),
      List(
        OtherOrig(5, Some(OtherOrig2(8, "11"))),
        OtherOrig(6, Some(OtherOrig2(9, "12"))),
        OtherOrig(7, Some(OtherOrig2(10, "13")))
      )
    )
    println(builder.getOrigValue(testOrig))
  }

  def dbTest: Unit = {
    ConnectionPool.singleton("jdbc:oracle:thin:@x.edss.ee:65035:mct1", "K$JMS", "YUaAH6a5ypPTcP3p")

    val registry: QAdapterRegistry = QAdapterRegistryFactory.apply(TestDbOrig :: Nil)
    val adapter: OrigDBAdapter = OracleOrigDBAdapter(registry)

    val factory = OracleOrigSchemaBuilderFactory(registry)
    val builder = factory.make(classOf[TestOrig], Nil)
    println(adapter.getSchema)
    println(adapter.patchSchema(builder.getSchemas))
    println(builder.getSchemas.mkString("\n===================\n"))
    val testOrig = TestOrig("1", 2, 3,
      Some("4"),
      List(
        OtherOrig(5, Some(OtherOrig2(8, "11"))),
        OtherOrig(6, Some(OtherOrig2(9, "12"))),
        OtherOrig(7, Some(OtherOrig2(10, "13")))
      )
    )
    adapter.putOrigs(builder.getOrigValue(testOrig))
    println(adapter.getOrig(builder.getMainSchema, "1"))
    /* println(
       DB localTx { implicit session ⇒ testSQL("test", List("1243", new ByteArrayInputStream(registry.byName(classOf[TestOrig].getName).encode(TestOrig("1", 2, 3, None, Nil))))).update().apply() }
     )*/
    //println(adapter.getOrig(OrigSchema(classOf[TestOrig].getName, "test", PrimaryKeySchema("pk", "") :: Nil, Nil), "1243"))
  }
}


@protocol object TestDbOrig extends Protocol {

  @Id(0x00a0) case class TestOrig(
    @Id(0x00a1) srcId: String,
    @Id(0x00a2) int: Int,
    @Id(0x00a3) long: Long,
    @Id(0x00a4) optString: Option[String],
    @Id(0x00a5) listOther: List[OtherOrig]
  )

  @Id(0x00a6) case class OtherOrig(
    @Id(0x00a7) int: Int,
    @Id(0x00a9) name: Option[OtherOrig2]
  )

  @Id(0x00b0) case class OtherOrig2(
    @Id(0x00b1) int: Int,
    @Id(0x00b2) name: String
  )

}