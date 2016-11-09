package examples


import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.meta._

@compileTimeOnly("@examples.Main not expanded")
class main extends scala.annotation.StaticAnnotation {
  inline def apply(defn: Any): Any = meta {
    val q"object $name { ..$stats }" = defn
    val main = q"def main(args: Array[String]): Unit = { ..$stats }"
    q"object $name { $main }"
  }
}

////

class Id(id: Int) extends StaticAnnotation
class scale(id: Int) extends StaticAnnotation

trait ProtoAdapterWithId {
  def id: Int
}

case class ProtoProp(
  defArg: String,
  sizeStatement: String,
  encodeStatement: String,
  initDecodeStatement: String,
  decodeCase: String,
  constructArg: String,
  resultFix: String
)
case class ProtoType(
    encodeStatement: (String,String), serializerType: String, empty: String, resultType: String,
    resultFix: String="", reduce: (String,String)=("","")
)
case class ProtoMessage(adapterName: String, classImpl: String, adapterImpl: String)
case class ProtoMods(id: Option[Int]=None)

@compileTimeOnly("not expanded")
class schema extends scala.annotation.StaticAnnotation {
  inline def apply(defn: Any): Any = meta {
    val q"object $objectName { ..$stats }" = defn
    val messages: List[ProtoMessage] = stats.flatMap{
      case q"import ..$i" ⇒ None
      case q"..$mods trait ${Type.Name(messageName)} { ..$stats }" =>
        val protoMods = mods./:(ProtoMods())((pMods,mod)⇒ mod match {
          case mod"@Id(${Lit(id:Int)})" if pMods.id.isEmpty ⇒
            pMods.copy(id=Option(id))
        })
        val props: List[ProtoProp] = stats.map{
          case q"..$mods def ${Term.Name(propName)}: $tpe" ⇒
            val Seq(mod"@Id(${Lit(id:Int)})") = mods
            val pt: ProtoType = tpe match {
              case t"Int" ⇒
                ProtoType(
                  encodeStatement = (s"if(prep_$propName != 0)", s"prep_$propName)"),
                  serializerType = "com.squareup.wire.ProtoAdapter.SINT32",
                  empty = "0",
                  resultType = "Int"
                )
              case t"Long" ⇒
                ProtoType(
                  encodeStatement = (s"if(prep_$propName != 0L)", s"prep_$propName)"),
                  serializerType = "com.squareup.wire.ProtoAdapter.SINT62",
                  empty = "0",
                  resultType = "Long"
                )
              case t"okio.ByteString" ⇒
                ProtoType(
                  encodeStatement = (s"if(prep_$propName.size > 0)", s"prep_$propName)"),
                  serializerType = "com.squareup.wire.ProtoAdapter.BYTES",
                  empty = "okio.ByteString.EMPTY",
                  resultType = "okio.ByteString"
                )
              case t"String" ⇒
                ProtoType(
                  encodeStatement = (s"if(prep_$propName.nonEmpty)", s"prep_$propName)"),
                  serializerType = "com.squareup.wire.ProtoAdapter.STRING",
                  empty = "\"\"",
                  resultType = "String"
                )
              case t"Option[${Type.Name(name)}]" ⇒
                ProtoType(
                  encodeStatement = (s"if(prep_$propName.nonEmpty)", s"prep_$propName.get)"),
                  serializerType = s"${name}ProtoAdapter",
                  empty = "None",
                  resultType = s"Option[$name]",
                  reduce=("Option(", ")")
                )
              case t"List[${Type.Name(name)}]" ⇒
                ProtoType(
                  encodeStatement = (s"prep_$propName.foreach(item => ","item))"),
                  serializerType = s"${name}ProtoAdapter",
                  empty = "Nil",
                  resultType = s"List[$name]",
                  resultFix = s"prep_$propName.reverse",
                  reduce = ("", s":: prep_$propName")
                )
              case t"Option[BigDecimal] @scale(${Lit(scale:Int)})" ⇒
                val name = "BigDecimal"
                ProtoType(
                  encodeStatement =
                    (s"if(prep_$propName.nonEmpty)", s"prep_$propName.get)"),
                  serializerType = s"${name}ProtoAdapter",
                  empty = "None",
                  resultType = s"Option[$name]",
                  reduce=("Option(", ")")
                )
              /*
              //ProtoType("com.squareup.wire.ProtoAdapter.BOOL", "\"\"", "String")
              //String, Option[Boolean], Option[Int], Option[BigDecimal], Option[Instant], Option[$]
              */
            }
            ProtoProp(
                defArg = s"$propName: ${pt.resultType}",
                sizeStatement = s"${pt.encodeStatement._1} res += ${pt.serializerType}.encodedSizeWithTag($id, ${pt.encodeStatement._2}",
                encodeStatement = s"${pt.encodeStatement._1} ${pt.serializerType}.encodeWithTag(writer, $id, ${pt.encodeStatement._2}",
                initDecodeStatement = s"var prep_$propName: ${pt.resultType} = ${pt.empty}",
                decodeCase = s"case $id => prep_$propName = ${pt.reduce._1} ${pt.serializerType}.decode(reader) ${pt.reduce._2}",
                constructArg = s"prep_$propName",
                resultFix = if(pt.resultFix.nonEmpty) s"prep_$propName = ${pt.resultFix}" else ""
            )
        }.toList
        val Sys = "Sys(.*)".r
        val resultType = messageName match { case Sys(v) ⇒ v case v ⇒ v }
        val classImpl = if(resultType != messageName) "" else
          s"""case class ${resultType}Impl(${props.map(_.defArg).mkString(",")}) extends $resultType"""
        val struct = s"""${resultType}Impl(${props.map(_.constructArg).mkString(",")})"""
        val adapterImpl = s"""
          object ${resultType}ProtoAdapter extends com.squareup.wire.ProtoAdapter[$resultType](
            com.squareup.wire.FieldEncoding.LENGTH_DELIMITED,
            classOf[$resultType]
          ) ${protoMods.id.map(_⇒"with ProtoAdapterWithId").getOrElse("")} {
            ${protoMods.id.map(i⇒s"def id = $i").getOrElse("")}
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
          }
        """
        val regAdapter = protoMods.id.map(_⇒s"${resultType}ProtoAdapter").getOrElse("")
        ProtoMessage(regAdapter, classImpl, adapterImpl) :: Nil
    }.toList
    val res = q"""
      object $objectName {
        ..$stats;
        ..${messages.map(_.classImpl).filter(_.nonEmpty).map(_.parse[Stat].get)};
        ..${messages.map(_.adapterImpl.parse[Stat].get)};
        def adapters: List[com.squareup.wire.ProtoAdapter[_<:Object] with ProtoAdapterWithId] =
          List(..${messages.map(_.adapterName).filter(_.nonEmpty).map(_.parse[Term].get)})
      }"""
    println(res)
    res
  }
}
