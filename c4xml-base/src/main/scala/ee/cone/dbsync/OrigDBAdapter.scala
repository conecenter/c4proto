package ee.cone.dbsync

import ee.cone.c4actor.Types.{NextOffset, SrcId}
import scalikejdbc.ConnectionPoolSettings

case class TableSchema(tableName: String, columnNames: List[String])

case class OrigSchema(level: Int, className: String, origTableName: String, pks: List[PrimaryKeySchema], fieldSchemas: List[FieldSchema], constraints: List[String] = Nil)

case class FieldSchema(fieldName: String, fieldType: String, creationStatement: String)

case class PrimaryKeySchema(pkName: String, pkType: String)

case class OrigValue(schema: OrigSchema, pks: List[Any], values: List[Any], delete: Boolean = false)

case class ConnectionSetting(name: Symbol, url: String, user: String, password: String, connectionPoolSettings: ConnectionPoolSettings)

trait OrigDBAdapter {
  def getSchema: List[TableSchema]

  def patchSchema(origSchemas: List[OrigSchema]): List[TableSchema]

  def getOffset: NextOffset

  def putOrigs(origs: List[OrigValue], offset: NextOffset): List[(OrigSchema, Int)]

  def getOrig(orig: OrigSchema, pk: String): (Option[Product], NextOffset)
}

trait OrigSchemaBuilder[Model <: Product] {
  def getOrigId: Long

  def getMainSchema: OrigSchema

  def getSchemas: List[OrigSchema]

  def getUpdateValue: Product ⇒ List[OrigValue]

  def getDeleteValue: SrcId ⇒ List[OrigValue]
}

trait OrigSchemaOption

trait OrigSchemaBuilderFactory {
  def db[Model <: Product](cl: Class[Model], options: List[OrigSchemaOption] = Nil): OrigSchemaBuilder[Model]
}

trait OrigSchemaBuildersApp {
  def builders: List[OrigSchemaBuilder[_ <: Product]] = Nil
}