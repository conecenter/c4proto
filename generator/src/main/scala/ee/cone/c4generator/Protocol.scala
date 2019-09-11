
package ee.cone.c4generator

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.meta.Term.Name
import scala.meta._

import scala.collection.immutable.Seq

case class ProtoProp(
  sizeStatement: String,
  encodeStatement: String,
  initDecodeStatement: String,
  decodeCase: String,
  constructArg: String,
  resultFix: String,
  metaProp: String,
  lensOpt: Option[String]
)
case class ProtoType(
  encodeStatement: (String,String), serializerType: String, empty: String, resultType: String,
  resultFix: String="", reduce: (String,String)=("","")
)
case class ProtoMessage(adapterName: String, statements: List[String], lenses: String)
case class ProtoMods(id: Option[Int]=None, category: List[String], shortName: Option[String] = None, genLens: Boolean = false)
case class FieldMods(id: Option[Int]=None, shortName: Option[String] = None, meta: List[String] = Nil)

object ProtocolGenerator extends Generator {
  def parseArgs: Seq[Seq[Term]] ⇒ List[String] =
    _.flatMap(_.collect{case q"${Name(name:String)}" ⇒ name}).toList

  def parseArgsWithApply: Seq[Seq[Term]] ⇒ List[String] =
    _.flatMap(_.map(_.toString())).toList

  def deOpt: Option[String] ⇒ String = {
    case None ⇒ "None"
    case Some(a) ⇒ s"""Some("$a")"""
  }

  def getLens(protocolName: String, origType: String, fieldId: Long, fieldName: String, fieldType: String, fieldProps: FieldMods): String =
    s"""  val $fieldName: ee.cone.c4actor.ProdLens[$protocolName.$origType, $fieldType] =
       |    ee.cone.c4actor.ProdLens.ofSet(
       |      _.$fieldName,
       |      v ⇒ _.copy($fieldName = v),
       |      "$protocolName.$origType.$fieldName",
       |      ee.cone.c4actor.IdMetaAttr($fieldId),
       |      ee.cone.c4actor.ClassesAttr(
       |        classOf[$protocolName.$origType].getName,
       |        classOf[$fieldType].getName
       |      )${if (fieldProps.meta.isEmpty) "" else fieldProps.meta.mkString(",\n      ", ",\n      ", "")}
       |    )""".stripMargin

  def getTypeProp(t: Type): String = {
    t match {
      case t"$tpe[..$tpesnel]" => s"""ee.cone.c4proto.TypeProp(classOf[$tpe[${tpesnel.map(_ ⇒ "_").mkString(", ")}]].getName, "$tpe", ${tpesnel.map(getTypeProp)})"""
      case t"$tpe" ⇒ s"""ee.cone.c4proto.TypeProp(classOf[$tpe].getName, "$tpe", Nil)"""
    }
  }

  def getCat(origType: String, isSys: Boolean): String = {
    if (isSys)
      "ee.cone.c4proto.S_Cat"
    else if (origType.charAt(1) == '_') {
      s"ee.cone.c4proto.${origType.charAt(0)}_Cat"
    }
    else
      throw new Exception(s"Invalid name for Orig: $origType, should start with 'W_' or unsupported orig type")
  }

  def get: Get = { case (code@q"@protocol(...$exprss) object ${objectNameNode@Term.Name(objectName)} extends ..$ext { ..$stats }", fileName) ⇒ Util.unBase(objectName,objectNameNode.pos.end){ objectName ⇒

      //println(t.structure)

    val args = parseArgs(exprss)

    val messages: List[ProtoMessage] = stats.flatMap{
      case q"import ..$i" ⇒ None
      case q"..$mods case class ${Type.Name(messageName)} ( ..$params )" =>
        val protoMods = mods./:(ProtoMods(category = args))((pMods,mod)⇒ mod match {
          case mod"@Cat(...$exprss)" ⇒
            val old = pMods.category
            pMods.copy(category = parseArgs(exprss) ::: old)
          case mod"@Id(${Lit(id:Int)})" if pMods.id.isEmpty ⇒
            pMods.copy(id=Option(id))
          case mod"@ShortName(${Lit(shortName:String)})" if pMods.shortName.isEmpty ⇒
            pMods.copy(shortName=Option(shortName))
          case mod"@GenLens" ⇒
            pMods.copy(genLens = true)
          case mod"@deprecated(...$notes)" ⇒ pMods
          case t: Tree ⇒
            Utils.parseError(t, "protocol", fileName)
        })
        val Sys = "Sys(.*)".r
        val (resultType, factoryName, isSys) = messageName match {
          case Sys(v) ⇒ (v, s"${v}Factory", true)
          case v ⇒ (v, v, false)
        }
        val doGenLens = protoMods.genLens
        val adapterOf: String=>String = {
          case "Int" ⇒ "com.squareup.wire.ProtoAdapter.SINT32"
          case "Long" ⇒ "com.squareup.wire.ProtoAdapter.SINT64"
          case "Boolean" ⇒ "com.squareup.wire.ProtoAdapter.BOOL"
          case "okio.ByteString" ⇒ "com.squareup.wire.ProtoAdapter.BYTES"
          case "String" ⇒ "com.squareup.wire.ProtoAdapter.STRING"
          case name ⇒ s"${name}ProtoAdapter"
        }
        val props: List[ProtoProp] = params.map{
          case param"..$mods ${Term.Name(propName)}: $tpeopt = $v" ⇒
            val fieldProps = mods./:(FieldMods())((fMods, mod) ⇒ mod match {
              case mod"@Id(${Lit(id:Int)})" ⇒
                fMods.copy(id = Option(id))
              case mod"@ShortName(${Lit(shortName:String)})" ⇒
                fMods.copy(shortName = Option(shortName))
              case mod"@deprecated(...$notes)" ⇒
                fMods
              case mod"@Meta(...$exprss)" ⇒
                val old = fMods.meta
                fMods.copy(meta = parseArgsWithApply(exprss) ::: old)
              case t: Tree ⇒
                Utils.parseError(t, "protocol", fileName)
            })
            val tp = tpeopt.asInstanceOf[Option[Type]].get
            /*
            val (tp,meta) = tpe.get match {
              case t"$tp @meta(..$ann)" ⇒ (tp,ann)
              case a ⇒ (a,Nil)
            }
            println(meta,meta.map(_.getClass))*/

            val pt: ProtoType = tp match {
              case t"Int" ⇒
                val name = "Int"
                ProtoType(
                  encodeStatement = (s"if(prep_$propName != 0)", s"prep_$propName)"),
                  serializerType = adapterOf(name),
                  empty = "0",
                  resultType = name
                )
              case t"Long" ⇒
                val name = "Long"
                ProtoType(
                  encodeStatement = (s"if(prep_$propName != 0L)", s"prep_$propName)"),
                  serializerType = adapterOf(name),
                  empty = "0",
                  resultType = name
                )
              case t"Boolean" ⇒
                val name = "Boolean"
                ProtoType(
                  encodeStatement = (s"if(prep_$propName)", s"prep_$propName)"),
                  serializerType = adapterOf(name),
                  empty = "false",
                  resultType = name
                )
              case t"okio.ByteString" ⇒
                val name = "okio.ByteString"
                ProtoType(
                  encodeStatement = (s"if(prep_$propName.size > 0)", s"prep_$propName)"),
                  serializerType = adapterOf(name),
                  empty = "okio.ByteString.EMPTY",
                  resultType = name
                )
              case t"String" ⇒
                val name = "String"
                ProtoType(
                  encodeStatement = (s"if(prep_$propName.nonEmpty)", s"prep_$propName)"),
                  serializerType = adapterOf(name),
                  empty = "\"\"",
                  resultType = name
                )
              case Type.Name(name) ⇒
                ProtoType(
                  encodeStatement = (s"if(prep_$propName != ${name}Empty)", s"prep_$propName)"),
                  serializerType = adapterOf(name),
                  empty = s"${name}Empty",
                  resultType = name
                )
              case t"Option[${Type.Name(name)}]" ⇒
                ProtoType(
                  encodeStatement = (s"if(prep_$propName.nonEmpty)", s"prep_$propName.get)"),
                  serializerType = adapterOf(name),
                  empty = "None",
                  resultType = s"Option[$name]",
                  reduce=("Option(", ")")
                )
              case t"List[${Type.Name(name)}]" ⇒
                ProtoType(
                  encodeStatement = (s"prep_$propName.foreach(item => ","item))"),
                  serializerType = adapterOf(name),
                  empty = "Nil",
                  resultType = s"List[$name]",
                  resultFix = s"prep_$propName.reverse",
                  reduce = ("", s":: prep_$propName")
                )
              /*
              //ProtoType("com.squareup.wire.ProtoAdapter.BOOL", "\"\"", "String")
              //String, Option[Boolean], Option[Int], Option[BigDecimal], Option[Instant], Option[$]
              */
            }
            val id = fieldProps.id.get
            ProtoProp(
              sizeStatement = s"${pt.encodeStatement._1} res += ${pt.serializerType}.encodedSizeWithTag($id, ${pt.encodeStatement._2}",
              encodeStatement = s"${pt.encodeStatement._1} ${pt.serializerType}.encodeWithTag(writer, $id, ${pt.encodeStatement._2}",
              initDecodeStatement = s"var prep_$propName: ${pt.resultType} = ${pt.empty}",
              decodeCase = s"case $id => prep_$propName = ${pt.reduce._1} ${pt.serializerType}.decode(reader) ${pt.reduce._2}",
              constructArg = s"prep_$propName",
              resultFix = if(pt.resultFix.nonEmpty) s"prep_$propName = ${pt.resultFix}" else "",
              metaProp = s"""ee.cone.c4proto.MetaProp($id,"$propName",${deOpt(fieldProps.shortName)},"${pt.resultType}", ${getTypeProp(tp)})""",
              if (doGenLens) Some(getLens(objectName, resultType, id, propName, pt.resultType, fieldProps)) else None
            )
        }.toList

        val struct = s"""${factoryName}(${props.map(_.constructArg).mkString(",")})"""
        val statements = List(
          s"""type ${resultType} = ${objectName}Base.${resultType}""",
          s"""val ${factoryName} = ${objectName}Base.${factoryName}""",
          s"""
          object ${resultType}ProtoAdapter extends com.squareup.wire.ProtoAdapter[$resultType](
            com.squareup.wire.FieldEncoding.LENGTH_DELIMITED,
            classOf[$resultType]
          ) with ee.cone.c4proto.HasId {
            def id = ${protoMods.id.getOrElse("throw new Exception")}
            def hasId = ${protoMods.id.nonEmpty}
            val ${messageName}_categories = List(${(getCat(resultType, isSys) :: protoMods.category).mkString(", ")}).distinct
            def categories = ${messageName}_categories
            def className = classOf[$resultType].getName
            def cl = classOf[$resultType]
            def shortName = ${deOpt(protoMods.shortName)}
            def encodedSize(value: $resultType): Int = {
              val $struct = value
              var res = 0;
              ${props.map(_.sizeStatement).mkString("\n")}
              res
            }
            def encode(writer: com.squareup.wire.ProtoWriter, value: $resultType) = {
              val $struct = value
              ${props.map(_.encodeStatement).mkString("\n")}
            }
            def decode(reader: com.squareup.wire.ProtoReader) = {
              ${props.map(_.initDecodeStatement).mkString("\n")};
              val token = reader.beginMessage();
              var done = false;
              while(!done) reader.nextTag() match {
                case -1 => done = true
                ${props.map(_.decodeCase).mkString("\n")}
                case _ => reader.peekFieldEncoding.rawProtoAdapter.decode(reader)
              }
              reader.endMessage(token)
              ${props.map(_.resultFix).mkString("\n")};
              $struct
            }
            def props = List(${props.map(_.metaProp).mkString(",")})
          }
        """)
        val regAdapter = s"${resultType}ProtoAdapter"
        val lensesLines = props.flatMap(_.lensOpt)
        val lenses =
          if (lensesLines.nonEmpty)
            s"""object ${resultType}Lenses {
               |  ${lensesLines.mkString("\n")}
               |}
             """.stripMargin
          else
            ""
        ProtoMessage(regAdapter, statements, lenses) :: Nil
    }.toList
    val imports = stats.collect{ case s@q"import ..$i" ⇒ s }
    val res = q"""
      object ${Term.Name(objectName)} extends Protocol {
        ..$imports;
        ..${messages.flatMap(_.statements).map(_.parse[Stat].get)};
        override def adapters = List(..${messages.map(_.adapterName).filter(_.nonEmpty).map(_.parse[Term].get)})
      }"""
    //println(res)
    //Util.comment(code)(cont) +
    GeneratedCode(res.syntax) :: messages.map(_.lenses).map(GeneratedCode.apply)
  }}
}
