package ee.cone.dbadapter

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4external.{ExtModelsApp, ExternalId, ExternalModel}

case class TableSchema(tableName: String, columnNames: List[String])

case class DBSchema(
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

case class FieldSchema(
  fieldId: Long,
  fieldName: String,
  fieldType: String,
  creationStatement: String,
  fieldRealName: Option[String] = None
)

case class PrimaryKeySchema(
  pkName: String,
  pkType: String,
  pkRealName: String
)

case class DBValue(
  schema: DBSchema,
  pks: List[Any],
  values: List[Any],
  delete: Boolean = false
)

trait DBAdapter {
  def externalId: ExternalId

  def getSchema: List[TableSchema]

  def patchSchema(origSchemas: List[DBSchema]): List[TableSchema]

  def getOffset: NextOffset

  def flush: Unit

  def putOrigs(origs: List[DBValue], offset: NextOffset): List[(DBSchema, Int)]

  def getOrigs(orig: DBSchema, pks: List[String]): List[Product]

  def getOrigBytes(orig: DBSchema, pks: List[String]): List[Update]

  def findOrigBy(orig: DBSchema, fieldId: Long, field: List[Any]): List[String]
}

trait DBSchemaBuilder[Model <: Product] {
  def externalId: ExternalId

  def getOrigId: Long

  def getOrigClName: String

  def getOrigCl: Class[Model]

  def getMainSchema: DBSchema

  def getSchemas: List[DBSchema]

  def getUpdateValue: Product ⇒ List[DBValue]

  def getDeleteValue: SrcId ⇒ List[DBValue]
}

trait DBSchemaOption

trait DBSchemaBuilderFactory {
  def db[Model <: Product](cl: Class[Model], options: List[DBSchemaOption] = Nil): DBSchemaBuilder[Model]
}

trait DBSchemaBuildersApp extends ExtModelsApp {
  def builders: List[DBSchemaBuilder[_ <: Product]] = Nil

  override def extModels: List[ExternalModel[_ <: Product]] = builders.map(b ⇒ ExternalModel(b.getOrigCl)) ::: super.extModels
}