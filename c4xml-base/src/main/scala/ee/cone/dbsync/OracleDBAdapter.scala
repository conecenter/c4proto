package ee.cone.dbsync

import java.io.ByteArrayInputStream

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4actor._
import ee.cone.c4assemble.ToPrimaryKey
import ee.cone.c4proto.{HasId, MetaProp}
import scalikejdbc._

case class OracleOrigDBAdapter(qAdapterRegistry: QAdapterRegistry, cs: ConnectionSetting) extends OrigDBAdapter {
  val poolSymbol: Symbol = cs.name
  ConnectionPool.add(poolSymbol, cs.url, cs.user, cs.password, cs.connectionPoolSettings)

  def getSchema: List[TableSchema] =
    NamedDB('conn) readOnly {
      implicit session ⇒
        sql"SELECT table_name, column_name FROM USER_TAB_COLUMNS".map(res ⇒ res.string("table_name").toUpperCase → res.string("column_name").toUpperCase).list().apply()
          .groupBy(_._1).toList.map(kv ⇒ TableSchema(kv._1, kv._2.map(_._2)))
    }

  val offsetSchema = OrigSchema(0, "c4offset", "c4offset",
    PrimaryKeySchema("id", "number(5,0)") :: Nil,
    FieldSchema("id", "number(5,0)", "id number(5,0) default 0 not null") :: FieldSchema("c4offset", "varchar2(16)", "c4offset varchar2(16) default '0000000000000000' not null") :: Nil
  )

  def getOffset: NextOffset =
    NamedDB('conn) readOnly { implicit session ⇒
      sql"select c4offset from c4offset where id = 0".map(_.string("c4offset")).single().apply().getOrElse("0000000000000000")
    }

  def patchSchema(origSchemas: List[OrigSchema]): List[TableSchema] = {
    val currentSchema = getSchema
    val currentSchemaMap = currentSchema.map(t ⇒ t.tableName → t.columnNames).toMap
    val (toUpdate, toCreate) = (offsetSchema :: origSchemas).partition(t ⇒ currentSchemaMap contains t.origTableName.toUpperCase)
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
    NamedDB('conn) localTx { implicit session ⇒
      sqls.map(SQL.apply).map(_.update.apply()).sum
    }
  }

  def createTable(orig: OrigSchema): String =
    s"create table ${orig.origTableName} (\n${orig.fieldSchemas.map(_.creationStatement).mkString(",\n")},\n primary key (${orig.pks.map(_.pkName).mkString(", ")})${("" :: orig.constraints).mkString(",\n")})"

  def alterTable(tableName: String, addColumns: List[String]): String =
    s"alter table $tableName add (\n${addColumns.mkString("\n")}\n)"


  def putOrigs(toWrite: List[OrigValue], offset: NextOffset): List[(OrigSchema, Int)] =
    NamedDB('conn) localTx { implicit session ⇒
      toWrite.groupBy(_.schema).toList.sortBy(_._1.level).map { case (orig, origs) ⇒
        val tableName = SQLSyntax.createUnsafely(orig.origTableName)
        val pkNames = orig.pks.map(_.pkName).map(SQLSyntax.createUnsafely(_))
        val keyValues = origs.map(_.pks).map(pkl ⇒ sqls"(${pkl})")
        sql"delete from ${tableName} where (${pkNames}) in (${keyValues})".update().apply()
        val columnNames = SQLSyntax.createUnsafely(orig.fieldSchemas.map(_.fieldName).mkString(", "))
        val sum = orig → origs.filterNot(_.delete).map(value ⇒ sql"insert into ${tableName} (${columnNames}) values (${value.values})").map(_.update().apply()).sum
        sql"delete from c4offset where id = 0".update().apply()
        sql"insert into c4offset values (0, ${offset})".update().apply()
        sum
      }
    }


  def getOrig(orig: OrigSchema, pk: String): (Option[Product], NextOffset) = {
    val adapter = qAdapterRegistry.byName(orig.className)
    NamedDB('conn) readOnly { implicit session ⇒
      val dbOrig = SQL(s"select blob from ${orig.origTableName} where (${orig.pks.map(_.pkName).mkString(", ")}) = ($pk)")
        .map(rt ⇒ rt.blob("blob"))
        .single().apply().map(bb ⇒ adapter.decode(bb.getBinaryStream))
      val offset = sql"select c4offset from c4offset where id = 0".map(_.string("c4offset")).single().apply().getOrElse("0000000000000000")
      (dbOrig, offset)
    }
  }
}

object LongHex {
  def apply(i: Long, prefix: String = ""): String = "%s0x%04x".format(prefix, i)
}

case class OracleLongString(fieldName: String)

case class OracleOrigSchemaBuilderFactory(qAdapterRegistry: QAdapterRegistry) extends OrigSchemaBuilderFactory {
  lazy val byName: Map[String, ProtoAdapter[Product] with HasId] = qAdapterRegistry.byName

  def db[Model <: Product](cl: Class[Model], options: List[OrigSchemaOption]): OrigSchemaBuilder[Model] = {
    val adapter = byName(cl.getName)
    val props: List[MetaProp] = adapter.props
    val id = adapter.id
    val origTableName = LongHex(id, "t")
    val primitives: List[FieldSchema] = parsePrimitives(props, options)
    val pk = primitives.head
    val origSchema = OrigSchema(
      0,
      cl.getName,
      origTableName,
      PrimaryKeySchema(pk.fieldName, pk.fieldType) :: Nil,
      FieldSchema("blob", "blob", "blob blob") :: primitives
    )
    val innerSchemas = parseInners(1, origSchema.origTableName, origSchema.pks, props, options)
    val schMap = (origSchema :: innerSchemas).map(sch ⇒ sch.origTableName → sch).toMap
    val getter = makeGetterOuter(cl, schMap)
    OracleOrigSchemaBuilder[Model](id, origSchema, innerSchemas, getter)
  }

  private def parseSchema(level: Int, parentName: String, parentPks: List[PrimaryKeySchema], clName: String, options: List[OrigSchemaOption]): (List[FieldSchema], List[OrigSchema]) = {
    val adapter = byName(clName)
    val props: List[MetaProp] = adapter.props
    val primitives: List[FieldSchema] = parsePrimitives(props, options)
    val children = parseInners(level, parentName, parentPks, props, options)
    (primitives, children)
  }

  private def isPrimitive(alias: String): Boolean =
    alias match {
      case "String" ⇒
        true
      case "Long" ⇒
        true
      case "Int" ⇒
        true
      case "Boolean" ⇒
        true
      case _ ⇒ false
    }

  private def parsePrimitives(id: Long, alias: String, name: String, options: List[OrigSchemaOption]): List[FieldSchema] = {
    alias match {
      case "Int" ⇒
        val name = LongHex(id, "I")
        val fType = "number(16,0)"
        FieldSchema(name, fType, s"$name $fType default 0 not null") :: Nil
      case "Long" ⇒
        val name = LongHex(id, "LO")
        val fType = "number(16,0)"
        FieldSchema(name, fType, s"$name $fType default 0 not null") :: Nil
      case "Boolean" ⇒
        val name = LongHex(id, "B")
        val fType = "number(1)"
        FieldSchema(name, fType, s"$name $fType default 0 check ($name in (0, 1)) not null") :: Nil
      case "String" ⇒
        val isLong = options.collectFirst { case a: OracleLongString if a.fieldName == name ⇒ true }.getOrElse(false)
        if (isLong) {
          val name = LongHex(id, "LS")
          val fType = "clob"
          FieldSchema(name, fType, s"$name $fType default '' not null") :: Nil
          Nil
        } else {
          val name = LongHex(id, "S")
          val fType = "varchar2(4000)"
          FieldSchema(name, fType, s"$name $fType default '' not null") :: Nil
        }
      case _ ⇒ Nil
    }
  }


  private def parsePrimitives(props: List[MetaProp], options: List[OrigSchemaOption]): List[FieldSchema] =
    props.flatMap(mp ⇒ parsePrimitives(mp.id, mp.typeProp.alias, mp.propName, options))

  private def parseInners(level: Int, parentName: String, parentPks: List[PrimaryKeySchema], props: List[MetaProp], options: List[OrigSchemaOption]): List[OrigSchema] =
    props.flatMap { mp ⇒
      mp.typeProp.alias match {
        case "Option" | "List" ⇒
          val tName = s"${parentName}ti${LongHex(mp.id)}"
          val pks =
            parentPks.map(pk ⇒ pk.copy(pkName = pk.pkName + "_pk")) :+ PrimaryKeySchema(s"${parentName}_index", "number(16,0)")
          val fields =
            parentPks.map(pk ⇒
              FieldSchema(pk.pkName + "_pk", pk.pkType, s"${pk.pkName}_pk ${pk.pkType} not null")
            ) :+ FieldSchema(parentName + "_index", "number(16,0)", s"${parentName}_index number(16,0) not null")
          val constraints =
            List(
              s"constraint ${tName}_parent foreign key (${parentPks.map(_.pkName + "_pk").mkString(", ")}) references $parentName (${parentPks.map(_.pkName).mkString(", ")}) on delete cascade"
            )
          val childType = mp.typeProp.children.head

          val (primitives, inners) =
            if (isPrimitive(childType.alias))
              (parsePrimitives(mp.id, childType.alias, mp.propName, options), Nil)
            else
              parseSchema(level + 1, tName, pks, childType.clName, options)
          val innerSchema =
            OrigSchema(
              level,
              childType.clName,
              tName,
              pks,
              fields ::: primitives,
              constraints
            )
          innerSchema :: inners
        case _ ⇒ Nil
      }
    }

  def makeGetterOuter[Model <: Product](cl: Class[Model], schemaMap: Map[String, OrigSchema]): OrigGetter = {
    val adapter = byName(cl.getName)
    val props: List[(MetaProp, Int)] = adapter.props.zipWithIndex
    val primProps = props.filter(p ⇒ isPrimitive(p._1.typeProp.alias))
    val id = adapter.id
    val tName = LongHex(id, "t")
    val schema = schemaMap(tName)
    val propsGetter: List[Product ⇒ Any] = primProps.map { case (prop, propId) ⇒
      prop.typeProp.alias match {
        case "Boolean" ⇒ (p: Product) ⇒
          if (p.productElement(propId).asInstanceOf[Boolean]) {
            1
          } else {
            0
          }
        case _ ⇒ (p: Product) ⇒ p.productElement(propId)
      }
    }
    val list: Product ⇒ List[Any] = (product: Product) ⇒ {
      propsGetter.map(_.apply(product))
    }
    val func: Product ⇒ OrigValue = (product: Product) ⇒ {
      OrigValue(schema, ToPrimaryKey(product) :: Nil, new ByteArrayInputStream(adapter.encode(product)) :: list(product))
    }
    val innerGetter: InnerOrigGetter = makeInners(tName, props, schemaMap)
    SimpleOrigGetter((product: Product) ⇒ func(product) :: innerGetter.get(product, ToPrimaryKey(product) :: Nil))
  }

  def makeInners(
    parentName: String,
    props: List[(MetaProp, Int)],
    schemaMap: Map[String, OrigSchema]
  ): InnerOrigGetter = {
    val funcs: List[(Product, List[Any]) ⇒ List[OrigValue]] = props.map { case (mp, id) ⇒
      mp.typeProp.alias match {
        case "Option" ⇒
          val tName = s"${parentName}ti${LongHex(mp.id)}"
          val schema = schemaMap(tName)
          val childType = mp.typeProp.children.head
          val innPrim = isPrimitive(childType.alias)
          if (innPrim) {
            val prep = preparePrimitive(childType.alias)
            (p: Product, pks: List[Any]) ⇒
              p.productElement(id) match {
                case Some(v) ⇒
                  val pksP = pks :+ 0
                  OrigValue(schema, pksP, pksP :+ prep(v)) :: Nil
                case None ⇒ Nil
              }
          } else {
            val (prim, child) = makeInner(tName, childType.clName, schemaMap)
            (p: Product, pks: List[Any]) ⇒
              p.productElement(id) match {
                case Some(v) ⇒
                  val inn = v.asInstanceOf[Product]
                  val pksP = pks :+ 0
                  OrigValue(schema, pksP, pksP ::: prim(inn)) :: child.get(inn, pksP)
                case None ⇒ Nil
              }
          }
        case "List" ⇒
          val tName = s"${parentName}ti${LongHex(mp.id)}"
          val schema = schemaMap(tName)
          val childType = mp.typeProp.children.head
          val innPrim = isPrimitive(childType.alias)
          if (innPrim) {
            val prep = preparePrimitive(childType.alias)
            (p: Product, pks: List[Any]) ⇒
              p.productElement(id) match {
                case Nil ⇒ Nil
                case l: List[_] ⇒
                  for {
                    (elem, i) ← l.zipWithIndex
                  } yield OrigValue(schema, pks :+ i, pks :+ i :+ prep(elem))
              }
          } else {
            val (prim, child) = makeInner(tName, childType.clName, schemaMap)
            (p: Product, pks: List[Any]) ⇒
              p.productElement(id) match {
                case Nil ⇒ Nil
                case l: List[_] ⇒
                  val lpk = l.asInstanceOf[List[Product]]
                  (for {
                    (elem, i) ← lpk.zipWithIndex
                  } yield {
                    val pksp = pks :+ i
                    OrigValue(schema, pksp, pksp ::: prim(elem)) :: child.get(elem, pksp)
                  }).flatten
              }
          }
        case _ ⇒ (p: Product, pks: List[Any]) ⇒ Nil
      }
    }
    InnerOrigGetter((p: Product, pks: List[Any]) ⇒ funcs.flatMap(_.apply(p, pks)))
  }

  def makePrimitives(props: List[(MetaProp, Int)]): Product ⇒ List[Any] = {
    val filtered = props.filter(p ⇒ isPrimitive(p._1.typeProp.alias))
    val list = filtered.map { case (mp, id) ⇒ makePrimitive(mp, id) }
    p: Product ⇒ list.map(_.apply(p))
  }

  def makePrimitive(mp: MetaProp, id: Int): Product ⇒ Any = {
    val prep = preparePrimitive(mp.typeProp.alias)
    p: Product ⇒ prep(p.productElement(id))
  }

  def preparePrimitive(alias: String): Any ⇒ Any =
    if (alias == "Boolean")
      (p: Any) ⇒ if (p.asInstanceOf[Boolean]) 1 else 0
    else
      identity

  def makeInner(parentName: String, clName: String, schemaMap: Map[String, OrigSchema]): (Product ⇒ List[Any], InnerOrigGetter) = {
    val adapter = byName(clName)
    val props: List[(MetaProp, Int)] = adapter.props.zipWithIndex
    val primitives: Product ⇒ List[Any] = makePrimitives(props)
    val child = makeInners(parentName, props, schemaMap)
    (primitives, child)
  }
}

case class SimpleOrigGetter(get: Product ⇒ List[OrigValue]) extends OrigGetter

case class InnerOrigGetter(get: (Product, List[Any]) ⇒ List[OrigValue])

trait OrigGetter {
  def get: Product ⇒ List[OrigValue]
}

case class OracleOrigSchemaBuilder[Model <: Product](
  getOrigId: Long,
  getMainSchema: OrigSchema,
  innerSchemas: List[OrigSchema],
  getter: OrigGetter /*,
  fields: Map[String, List[FieldSchema]]*/
) extends OrigSchemaBuilder[Model] {
  def getUpdateValue: Product ⇒ List[OrigValue] = getter.get
  def getDeleteValue: SrcId ⇒ List[OrigValue] = pk ⇒ OrigValue(getMainSchema, pk :: Nil, Nil, delete = true) :: Nil
  def getSchemas: List[OrigSchema] = getMainSchema :: innerSchemas
}
