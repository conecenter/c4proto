package ee.cone.dbrequest

import scalikejdbc._

object LongHex {
  def apply(i: Long, prefix: String = ""): String = "%s0x%04x".format(prefix, i)
}

case object OracleOrigDBAdapter extends OrigDBAdapter {
  def getSchema: List[TableSchema] =
    DB readOnly {
      implicit session ⇒
        sql"SELECT table_name, column_name FROM USER_TAB_COLUMNS".map(res ⇒ res.string("table_name").toUpperCase → res.string("column_name").toUpperCase).list().apply()
          .groupBy(_._1).toList.map(kv ⇒ TableSchema(kv._1, kv._2.map(_._2)))
    }

  def patchSchema(origSchemas: List[OrigSchema]): List[TableSchema] = {
    val currentSchema = getSchema
    val currentSchemaMap = currentSchema.map(t ⇒ t.tableName → t.columnNames).toMap
    val (toUpdate, toCreate) = origSchemas.partition(t ⇒ currentSchemaMap contains t.origTableName.toUpperCase)
    val creations = toCreate.map(createTable)
    val alters = toUpdate.map { orig ⇒
      val columns = orig.fieldSchemas.filterNot(f ⇒ currentSchemaMap(orig.origTableName.toUpperCase) contains f.fieldName.toUpperCase).map(_.creationStatement)
      if (columns.nonEmpty)
        alterTable(orig.origTableName, columns)
      else
        ""
    }.filter(_.nonEmpty)
    requestExecution(creations ++ alters)
    getSchema
  }

  def requestExecution(sqls: List[String]): Int = {
    DB localTx { implicit session ⇒
      sqls.map(SQL.apply).map(_.update.apply()).sum
    }
  }

  def createTable(orig: OrigSchema): String =
    s"create table ${orig.origTableName} (\n${orig.fieldSchemas.map(_.creationStatement).mkString(",\n")},\n primary key (${orig.pkName})${("" :: orig.extraConstraints).mkString(",\n")})"

  def alterTable(tableName: String, addColumns: List[String]): String =
    s"alter table $tableName add (\n${addColumns.mkString("\n")}\n)"


  def putOrig(orig: OrigSchema, origs: List[OrigValue]): Int =
    DB localTx { implicit session ⇒
      SQL(s"delete from ${orig.origTableName} where ${orig.pkName} in (${origs.map(_.pk).mkString(", ")})").update().apply()
      SQL(s"insert into ${orig.origTableName} (${orig.fieldSchemas.map(_.fieldName).mkString(", ")}) values ${origs.map(_.value).mkString(", ")}").update().apply()
    }

  def getOrigFields(orig: OrigSchema, pk: String, columns: List[String]): Option[Map[String, Any]] =
    DB readOnly { implicit session ⇒
      SQL(s"select ${columns.mkString(",")} from ${orig.origTableName} where ${orig.pkName} = $pk").map(rt ⇒ rt.toMap()).single().apply()
    }
}
