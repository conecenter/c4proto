/*
package ee.cone.tests

import java.io.ByteArrayInputStream
import java.util

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.{CheckedMap, QAdapterRegistry, QAdapterRegistryFactory}
import ee.cone.c4proto.{HasId, Id, Protocol, protocol}
import ee.cone.dbadapter._
import ee.cone.tests.TestDbOrig.{OtherOrig, OtherOrig2, TestOrig}
import scalikejdbc._


object DBTest extends App {
  GlobalSettings.loggingSQLAndTime = LoggingSQLAndTimeSettings(
    singleLineMode = true,
    logLevel = 'debug
  )
  dbTest(args)
  val registry: QAdapterRegistry = /*QAdapterRegistryFactory.apply(TestDbOrig :: Nil)*/ {
    val adapters = TestDbOrig.adapters.asInstanceOf[List[ProtoAdapter[Product] with HasId]]
    val byName = CheckedMap(adapters.map(a ⇒ a.className → a))
    val byId = CheckedMap(adapters.filter(_.hasId).map(a ⇒ a.id → a))
    new QAdapterRegistry(byName, byId)
  }

  val factory = OracleOrigSchemaBuilderFactory(registry)
  val builder = factory.db(classOf[TestOrig], Nil)
  val testOrig = TestOrig("1", 2, 3,
    Some("4"),
    List(
      OtherOrig(5, Some(OtherOrig2(8, "11"))),
      OtherOrig(6, Some(OtherOrig2(9, "12"))),
      OtherOrig(7, Some(OtherOrig2(10, "13")))
    )
  )
  //println(builder.getOrig(testOrig))

  def dbTest(args: Array[String]): Unit = {
    val settings = ConnectionPoolSettings(
      initialSize = 5,
      maxSize = 20,
      connectionTimeoutMillis = 3000L,
      validationQuery = "select 1 from dual"
    )
    val url = args(0)
    val user = args(1)
    val pass = args(2)
    println(util.Arrays.asList(args))
    val connectionsetting = ConnectionSetting('conn, url, user, pass, settings)

    val registry: QAdapterRegistry = //QAdapterRegistryFactory.apply(TestDbOrig :: Nil)
    {
      val adapters = TestDbOrig.adapters.asInstanceOf[List[ProtoAdapter[Product] with HasId]]
      val byName = CheckedMap(adapters.map(a ⇒ a.className → a))
      val byId = CheckedMap(adapters.filter(_.hasId).map(a ⇒ a.id → a))
      new QAdapterRegistry(byName, byId)
    }
    val adapter: DBAdapter = OracleDBAdapter(registry, connectionsetting)
    val factory = OracleOrigSchemaBuilderFactory(registry)
    val builder = factory.db(classOf[TestOrig], Nil)
    println(adapter.getSchema)
    println(adapter.patchSchema(builder.getSchemas))
    val testOrig = TestOrig("2", 2, 3,
      Some("4"),
      List(
        OtherOrig(5, Some(OtherOrig2(8, "11"))),
        OtherOrig(6, Some(OtherOrig2(9, "12"))),
        OtherOrig(7, Some(OtherOrig2(10, "13")))
      )
    )
    adapter.putOrigs(builder.getUpdateValue(testOrig), "00000000000000ac")
    println(adapter.getOrig(builder.getMainSchema, "1"))
    println(adapter.findOrigBy(builder.getMainSchema, 162, 2 :: Nil))
    println(adapter.getOffset)
    println("FLUSH")
    println(adapter.flush)
    /* println(
       DB localTx { implicit session ⇒ testSQL("test", List("1243", new ByteArrayInputStream(registry.byName(classOf[TestOrig].getName).encode(TestOrig("1", 2, 3, None, Nil))))).update().apply() }
     )*/
    //println(adapter.getOrig(OrigSchema(classOf[TestOrig].getName, "test", PrimaryKeySchema("pk", "") :: Nil, Nil), "1243"))
  }
}


object TestDbOrig {

  @Id(160) case class TestOrig(
    @Id(161) srcId: String,
    @Id(162) int: Int,
    @Id(163) long: Long,
    @Id(164) optString: Option[String],
    @Id(165) listOther: List[OtherOrig])

  @Id(166) case class OtherOrig(@Id(167) int: Int, @Id(169) name: Option[OtherOrig2])

  @Id(176) case class OtherOrig2(@Id(177) int: Int, @Id(178) name: String)

  object TestOrigProtoAdapter extends com.squareup.wire.ProtoAdapter[TestOrig](com.squareup.wire.FieldEncoding.LENGTH_DELIMITED, classOf[TestOrig]) with ee.cone.c4proto.HasId {
    def id = 160
    def hasId = true
    val TestOrig_categories = List().distinct
    def categories = TestOrig_categories
    def className = classOf[TestOrig].getName
    def encodedSize(value: TestOrig): Int = {
      val TestOrig(prep_srcId, prep_int, prep_long, prep_optString, prep_listOther) = value
      var res = 0
      if (prep_srcId.nonEmpty) res += com.squareup.wire.ProtoAdapter.STRING.encodedSizeWithTag(161, prep_srcId)
      if (prep_int != 0) res += com.squareup.wire.ProtoAdapter.SINT32.encodedSizeWithTag(162, prep_int)
      if (prep_long != 0L) res += com.squareup.wire.ProtoAdapter.SINT64.encodedSizeWithTag(163, prep_long)
      if (prep_optString.nonEmpty) res += com.squareup.wire.ProtoAdapter.STRING.encodedSizeWithTag(164, prep_optString.get)
      prep_listOther.foreach(item => res += OtherOrigProtoAdapter.encodedSizeWithTag(165, item))
      res
    }
    def encode(writer: com.squareup.wire.ProtoWriter, value: TestOrig) = {
      val TestOrig(prep_srcId, prep_int, prep_long, prep_optString, prep_listOther) = value
      if (prep_srcId.nonEmpty) com.squareup.wire.ProtoAdapter.STRING.encodeWithTag(writer, 161, prep_srcId)
      if (prep_int != 0) com.squareup.wire.ProtoAdapter.SINT32.encodeWithTag(writer, 162, prep_int)
      if (prep_long != 0L) com.squareup.wire.ProtoAdapter.SINT64.encodeWithTag(writer, 163, prep_long)
      if (prep_optString.nonEmpty) com.squareup.wire.ProtoAdapter.STRING.encodeWithTag(writer, 164, prep_optString.get)
      prep_listOther.foreach(item => OtherOrigProtoAdapter.encodeWithTag(writer, 165, item))
    }
    def decode(reader: com.squareup.wire.ProtoReader) = {
      var prep_srcId: String = ""
      var prep_int: Int = 0
      var prep_long: Long = 0
      var prep_optString: Option[String] = None
      var prep_listOther: List[OtherOrig] = Nil
      val token = reader.beginMessage()
      var done = false
      while (!done) reader.nextTag() match {
        case -1 =>
          done = true
        case 161 =>
          prep_srcId = com.squareup.wire.ProtoAdapter.STRING.decode(reader)
        case 162 =>
          prep_int = com.squareup.wire.ProtoAdapter.SINT32.decode(reader)
        case 163 =>
          prep_long = com.squareup.wire.ProtoAdapter.SINT64.decode(reader)
        case 164 =>
          prep_optString = Option(com.squareup.wire.ProtoAdapter.STRING.decode(reader))
        case 165 =>
          prep_listOther = OtherOrigProtoAdapter.decode(reader) :: prep_listOther
        case _ =>
          reader.peekFieldEncoding.rawProtoAdapter.decode(reader)
      }
      reader.endMessage(token)
      prep_listOther = prep_listOther.reverse
      TestOrig(prep_srcId, prep_int, prep_long, prep_optString, prep_listOther)
    }
    def props = List(ee.cone.c4proto.MetaProp(161, "srcId", "String", ee.cone.c4proto.TypeProp(classOf[String].getName, "String", Nil)), ee.cone.c4proto.MetaProp(162, "int", "Int", ee.cone.c4proto.TypeProp(classOf[Int].getName, "Int", Nil)), ee.cone.c4proto.MetaProp(163, "long", "Long", ee.cone.c4proto.TypeProp(classOf[Long].getName, "Long", Nil)), ee.cone.c4proto.MetaProp(164, "optString", "Option[String]", ee.cone.c4proto.TypeProp(classOf[Option[_]].getName, "Option", List(ee.cone.c4proto.TypeProp(classOf[String].getName, "String", Nil)))), ee.cone.c4proto.MetaProp(165, "listOther", "List[OtherOrig]", ee.cone.c4proto.TypeProp(classOf[List[_]].getName, "List", List(ee.cone.c4proto.TypeProp(classOf[OtherOrig].getName, "OtherOrig", Nil)))))
  }

  object OtherOrigProtoAdapter extends com.squareup.wire.ProtoAdapter[OtherOrig](com.squareup.wire.FieldEncoding.LENGTH_DELIMITED, classOf[OtherOrig]) with ee.cone.c4proto.HasId {
    def id = 166
    def hasId = true
    val OtherOrig_categories = List().distinct
    def categories = OtherOrig_categories
    def className = classOf[OtherOrig].getName
    def encodedSize(value: OtherOrig): Int = {
      val OtherOrig(prep_int, prep_name) = value
      var res = 0
      if (prep_int != 0) res += com.squareup.wire.ProtoAdapter.SINT32.encodedSizeWithTag(167, prep_int)
      if (prep_name.nonEmpty) res += OtherOrig2ProtoAdapter.encodedSizeWithTag(169, prep_name.get)
      res
    }
    def encode(writer: com.squareup.wire.ProtoWriter, value: OtherOrig) = {
      val OtherOrig(prep_int, prep_name) = value
      if (prep_int != 0) com.squareup.wire.ProtoAdapter.SINT32.encodeWithTag(writer, 167, prep_int)
      if (prep_name.nonEmpty) OtherOrig2ProtoAdapter.encodeWithTag(writer, 169, prep_name.get)
    }
    def decode(reader: com.squareup.wire.ProtoReader) = {
      var prep_int: Int = 0
      var prep_name: Option[OtherOrig2] = None
      val token = reader.beginMessage()
      var done = false
      while (!done) reader.nextTag() match {
        case -1 =>
          done = true
        case 167 =>
          prep_int = com.squareup.wire.ProtoAdapter.SINT32.decode(reader)
        case 169 =>
          prep_name = Option(OtherOrig2ProtoAdapter.decode(reader))
        case _ =>
          reader.peekFieldEncoding.rawProtoAdapter.decode(reader)
      }
      reader.endMessage(token)
      OtherOrig(prep_int, prep_name)
    }
    def props = List(ee.cone.c4proto.MetaProp(167, "int", "Int", ee.cone.c4proto.TypeProp(classOf[Int].getName, "Int", Nil)), ee.cone.c4proto.MetaProp(169, "name", "Option[OtherOrig2]", ee.cone.c4proto.TypeProp(classOf[Option[_]].getName, "Option", List(ee.cone.c4proto.TypeProp(classOf[OtherOrig2].getName, "OtherOrig2", Nil)))))
  }

  object OtherOrig2ProtoAdapter extends com.squareup.wire.ProtoAdapter[OtherOrig2](com.squareup.wire.FieldEncoding.LENGTH_DELIMITED, classOf[OtherOrig2]) with ee.cone.c4proto.HasId {
    def id = 176
    def hasId = true
    val OtherOrig2_categories = List().distinct
    def categories = OtherOrig2_categories
    def className = classOf[OtherOrig2].getName
    def encodedSize(value: OtherOrig2): Int = {
      val OtherOrig2(prep_int, prep_name) = value
      var res = 0
      if (prep_int != 0) res += com.squareup.wire.ProtoAdapter.SINT32.encodedSizeWithTag(177, prep_int)
      if (prep_name.nonEmpty) res += com.squareup.wire.ProtoAdapter.STRING.encodedSizeWithTag(178, prep_name)
      res
    }
    def encode(writer: com.squareup.wire.ProtoWriter, value: OtherOrig2) = {
      val OtherOrig2(prep_int, prep_name) = value
      if (prep_int != 0) com.squareup.wire.ProtoAdapter.SINT32.encodeWithTag(writer, 177, prep_int)
      if (prep_name.nonEmpty) com.squareup.wire.ProtoAdapter.STRING.encodeWithTag(writer, 178, prep_name)
    }
    def decode(reader: com.squareup.wire.ProtoReader) = {
      var prep_int: Int = 0
      var prep_name: String = ""
      val token = reader.beginMessage()
      var done = false
      while (!done) reader.nextTag() match {
        case -1 =>
          done = true
        case 177 =>
          prep_int = com.squareup.wire.ProtoAdapter.SINT32.decode(reader)
        case 178 =>
          prep_name = com.squareup.wire.ProtoAdapter.STRING.decode(reader)
        case _ =>
          reader.peekFieldEncoding.rawProtoAdapter.decode(reader)
      }
      reader.endMessage(token)
      OtherOrig2(prep_int, prep_name)
    }
    def props = List(ee.cone.c4proto.MetaProp(177, "int", "Int", ee.cone.c4proto.TypeProp(classOf[Int].getName, "Int", Nil)), ee.cone.c4proto.MetaProp(178, "name", "String", ee.cone.c4proto.TypeProp(classOf[String].getName, "String", Nil)))
  }

  def adapters = List(TestOrigProtoAdapter, OtherOrigProtoAdapter, OtherOrig2ProtoAdapter)
}*/
