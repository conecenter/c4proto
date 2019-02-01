package ee.cone.dbadapter

import ee.cone.c4actor.ExtModelsApp
import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.{NextOffset, SrcId}

case class TableSchema(tableName: String, columnNames: List[String])

case class OrigSchema(
  level: Int,
  origViewName: String,
  className: String,
  origTableName: String,
  pks: List[PrimaryKeySchema],
  fieldSchemas: List[FieldSchema],
  constraints: List[String] = Nil
) {
  private val fieldByIdMap: Map[Long, FieldSchema] = fieldSchemas.map(t ⇒ t.fieldId → t).toMap

  def fieldById: Long ⇒ FieldSchema = fieldByIdMap
}

case class FieldSchema(fieldId: Long, fieldName: String, fieldType: String, creationStatement: String, fieldRealName: Option[String] = None)

case class PrimaryKeySchema(pkName: String, pkType: String, pkRealName: String)

case class OrigValue(schema: OrigSchema, pks: List[Any], values: List[Any], delete: Boolean = false)

trait DBAdapter {
  def externalName:String

  def getSchema: List[TableSchema]

  def patchSchema(origSchemas: List[OrigSchema]): List[TableSchema]

  def getOffset: NextOffset

  def flush: Unit

  def putOrigs(origs: List[OrigValue], offset: NextOffset): List[(OrigSchema, Int)]

  def getOrigs(orig: OrigSchema, pks: List[String]): List[Product]

  def getOrigBytes(orig: OrigSchema, pks: List[String]): List[Update]

  def findOrigBy(orig: OrigSchema, fieldId: Long, field: List[Any]): List[String]
}

trait OrigSchemaBuilder[Model <: Product] {
  def getOrigId: Long

  def getOrigClName: String

  def getOrigCl: Class[Model]

  def getMainSchema: OrigSchema

  def getSchemas: List[OrigSchema]

  def getUpdateValue: Product ⇒ List[OrigValue]

  def getDeleteValue: SrcId ⇒ List[OrigValue]
}

trait OrigSchemaOption

trait OrigSchemaBuilderFactory {
  def db[Model <: Product](cl: Class[Model], options: List[OrigSchemaOption] = Nil): OrigSchemaBuilder[Model]
}

trait OrigSchemaBuildersApp extends ExtModelsApp {
  def builders: List[OrigSchemaBuilder[_ <: Product]] = Nil

  override def external: List[Class[_ <: Product]] = builders.map(_.getOrigCl) ::: super.external
}