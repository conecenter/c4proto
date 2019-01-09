package ee.cone.dbrequest

case class TableSchema(tableName: String, columnNames: List[String])

case class OrigSchema(origTableName: String, pkName: String, fieldSchemas: List[FieldSchema], extraConstraints: List[String] = Nil)
case class FieldSchema(fieldName: String, creationStatement: String)
case class OrigValue(pk: String, value: String)

trait OrigDBAdapter {
  def getSchema: List[TableSchema]

  def patchSchema(origSchemas: List[OrigSchema]): List[TableSchema]

  def putOrig(orig: OrigSchema, origs: List[OrigValue]): Int

  def getOrigFields(orig: OrigSchema, pk: String, columns: List[String]): Option[Map[String, Any]]
}
