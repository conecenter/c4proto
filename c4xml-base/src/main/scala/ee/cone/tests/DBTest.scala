package ee.cone.tests

import ee.cone.dbrequest.PSQLSchemaGetter
import scalikejdbc._

object DBTest {
  def main(args: Array[String]): Unit = {
    ConnectionPool.singleton("jdbc:postgresql://localhost:5432/postgres", "postgres", "ilya239")
    val results = PSQLSchemaGetter.getListRO
      println(results)
  }
}
