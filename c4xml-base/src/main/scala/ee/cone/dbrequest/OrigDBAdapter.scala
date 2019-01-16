package ee.cone.dbrequest

case class TableSchema(tableName: String, columnNames: List[String])

case class OrigSchema(level: Int, className: String, origTableName: String, pks: List[PrimaryKeySchema], fieldSchemas: List[FieldSchema], constraints: List[String] = Nil)

case class FieldSchema(fieldName: String, fieldType: String, creationStatement: String)

case class PrimaryKeySchema(pkName: String, pkType: String)

case class OrigValue(schema: OrigSchema, pks: List[Any], values: List[Any]) {
  override def toString: String = s"($pks,$values)"
}

trait OrigDBAdapter {
  def getSchema: List[TableSchema]

  def patchSchema(origSchemas: List[OrigSchema]): List[TableSchema]

  def putOrigs(origs: List[OrigValue]): List[(OrigSchema, Int)]

  def getOrig(orig: OrigSchema, pk: String): Option[Product]
}

trait OrigSchemaBuilder[Model <: Product] {
  def getMainSchema: OrigSchema

  def getSchemas: List[OrigSchema]

  def getOrigValue: Model ⇒ List[OrigValue]

  def getPk: Model ⇒ String
}

trait OrigSchemaOption

trait OrigSchemaBuilderFactory {
  def make[Model <: Product](cl: Class[Model], options: List[OrigSchemaOption] = Nil): OrigSchemaBuilder[Model]
}