
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
  metaProp: String
)
case class ProtoType(
  encodeStatement: (String,String), serializerType: String, empty: String, resultType: String,
  resultFix: String="", reduce: (String,String)=("","")
)
case class ProtoMessage(adapterName: String, adapterImpl: String)
case class ProtoMods(id: Option[Int]=None, category: List[String])

object ProtocolGenerator extends Generator {
  def parseArgs: Seq[Seq[Term]] ⇒ List[String] =
    _.flatMap(_.collect{case q"${Name(name:String)}" ⇒ name}).toList

  def get: Get = {
    case q"@protocol(...$exprss) object $objectName extends ..$ext { ..$stats }" ⇒

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
          case mod"@deprecated" ⇒ pMods
        })
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
            val Seq(mod"@Id(${Lit(id:Int)})") = mods
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
            def parseType(t: Type): String = {
              t match {
                case t"$tpe[..$tpesnel]" => s"""ee.cone.c4proto.TypeProp(classOf[$tpe[${tpesnel.map(_ ⇒ "_").mkString(", ")}]].getName, "$tpe", ${tpesnel.map(parseType)})"""
                case t"$tpe" ⇒ s"""ee.cone.c4proto.TypeProp(classOf[$tpe].getName, "$tpe", Nil)"""
              }
            }

            ProtoProp(
              sizeStatement = s"${pt.encodeStatement._1} res += ${pt.serializerType}.encodedSizeWithTag($id, ${pt.encodeStatement._2}",
              encodeStatement = s"${pt.encodeStatement._1} ${pt.serializerType}.encodeWithTag(writer, $id, ${pt.encodeStatement._2}",
              initDecodeStatement = s"var prep_$propName: ${pt.resultType} = ${pt.empty}",
              decodeCase = s"case $id => prep_$propName = ${pt.reduce._1} ${pt.serializerType}.decode(reader) ${pt.reduce._2}",
              constructArg = s"prep_$propName",
              resultFix = if(pt.resultFix.nonEmpty) s"prep_$propName = ${pt.resultFix}" else "",
              metaProp = s"""ee.cone.c4proto.MetaProp($id,"$propName","${pt.resultType}", ${parseType(tp)})"""
            )
        }.toList
        val Sys = "Sys(.*)".r
        val (resultType,factoryName) = messageName match { case Sys(v) ⇒ (v,s"${v}Factory") case v ⇒ (v,v) }
        val struct = s"""${factoryName}(${props.map(_.constructArg).mkString(",")})"""
        val adapterImpl = s"""
          object ${resultType}ProtoAdapter extends com.squareup.wire.ProtoAdapter[$resultType](
            com.squareup.wire.FieldEncoding.LENGTH_DELIMITED,
            classOf[$resultType]
          ) with ee.cone.c4proto.HasId {
            def id = ${protoMods.id.getOrElse("throw new Exception")}
            def hasId = ${protoMods.id.nonEmpty}
            val ${messageName}_categories = List(${protoMods.category.mkString(", ")}).distinct
            def categories = ${messageName}_categories
            def className = classOf[$resultType].getName
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
        """
        val regAdapter = s"${resultType}ProtoAdapter"
        ProtoMessage(regAdapter, adapterImpl) :: Nil
    }.toList
    val res = q"""
      object $objectName extends ..$ext {
        ..$stats;
        ..${messages.map(_.adapterImpl.parse[Stat].get)};
        override def adapters = List(..${messages.map(_.adapterName).filter(_.nonEmpty).map(_.parse[Term].get)})
      }"""
    //println(res)
    res.syntax
  }
}
