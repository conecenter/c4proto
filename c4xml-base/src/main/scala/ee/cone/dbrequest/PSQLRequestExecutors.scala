package ee.cone.dbrequest

import ee.cone.xsd.OrigId
import scalikejdbc.{NoExtractor, SQL, WrappedResultSet}

case class DBSchema(tableName: String, columnNames: List[String])

case object PSQLSchemaGetter extends StaticDBAsk[DBSchema] {
  def resultHandler: WrappedResultSet ⇒ DBSchema = r ⇒ DBSchema(r.string("table_name"), r.array("columns").getArray.asInstanceOf[Array[Object]].map(_.toString).toList)
  def request: SQL[Nothing, NoExtractor] = SQL("select table_name, array_agg(column_name) as columns from INFORMATION_SCHEMA.COLUMNS where table_schema = 'public' group by table_name")
}

case class OrigSchema(origName: String, origId: Long, origFields: List[FieldSchema])
case class FieldSchema(fieldName: String, fieldId: Long, fieldType: String)

case class PSQLCreateTable(schemaAsk: StaticDBAsk[DBSchema], localSchema: List[OrigSchema]) extends DynamicDBUpdate {
  def updateSchema: Int = {
    val dbSchema = schemaAsk.getListRO
    1
  }

  def compareSchemas(localSchema: List[OrigSchema], dbSchema: List[DBSchema]): String = {
    val existingTables = dbSchema.map(_.tableName).toSet
    ""
  }

  /*def createTable(orig: OrigSchema): String = {
    s"""create table t${OrigId(orig.origId)}
       |(
       |${orig.origFields.zipWithIndex.map(columnDefinition).mkString(",\n")}
       |)
     """.stripMargin
  }

  def columnDefinition(field : FieldSchema, primary: Boolean): String =
    field.fieldType match {
      case "String" ⇒ s"s${field.fieldId} default ''"
      case "Int" ⇒
      case "Long" ⇒
      case "Boolean" ⇒
    }*/
}
