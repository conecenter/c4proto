package ee.cone.tests

import ee.cone.dbrequest._
import scalikejdbc._

object DBTest {
  def main(args: Array[String]): Unit = {
    ConnectionPool.singleton("jdbc:oracle:thin:@x.edss.ee:65035:mct1", "K$JMS", "YUaAH6a5ypPTcP3p")
    val adapter: OrigDBAdapter = OracleOrigDBAdapter
    val origSchema = OrigSchema("t0xa001", "s0xa001", List(FieldSchema("s0xa001", "s0xa001 varchar(10) default '' not null"),
      FieldSchema("i0xa003", "i0xa003 int         default 0  not null")
    )
    )
    println(adapter.getSchema)
    println(adapter.patchSchema(
      List(origSchema
      )
    )
    )
    println(adapter.putOrig(origSchema, List(OrigValue("'124'", "('124', 2)"))
    )
    )
    println(adapter.getOrigFields(origSchema, "'124'", origSchema.fieldSchemas.map(_.fieldName)))
  }
}
