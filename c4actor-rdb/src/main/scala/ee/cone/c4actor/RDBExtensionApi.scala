
package ee.cone.c4actor

trait ExternalDBFactory {
  def create(wrap: (()⇒java.sql.Connection)⇒RConnectionPool): RConnectionPool
}

trait RConnectionPool {
  def doWith[T](f: RConnection⇒T): T
}

trait RConnection {
  def execute(code: String, bind: List[Object]): Unit
  def executeQuery(code: String, cols: List[String], bind: List[Object]): List[Map[String,Object]]
}

trait Need
case class DropType(name: String, attributes: List[DropTypeAttr], uses: List[DropType])
case class DropTypeAttr(attrName: String, attrTypeName: String)
case class NeedCode(drop: String,  ddl: String) extends Need

trait DDLUtil {
  def createOrReplace(key: String, args: String, code: String): NeedCode
}

trait DDLGeneratorHooks {
  def function(
    fName: String, args: String, resType: String, body: String
  ): NeedCode
  def toDbName(tp: String, mod: String, size: Int): String
  def objectType: String
  def loop(enc: String): String
}

trait DDLGenerator {
  def generate(wasTypes: List[DropType], wasFunctionNameList: List[String]): List[String]
}