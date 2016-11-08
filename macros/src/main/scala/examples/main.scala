package examples


import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.meta._
import scala.collection.immutable
import scala.meta.Ctor.Ref.Name
import scala.meta.Defn.Def

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
  resultFix: String,
  isSystem: Boolean
)

case class ProtoType(
    encodeStatement: String, serializerType: String, empty: String, resultType: String,
    resultFix: String="", reduce: (String,String)=("",""), isSystem: Boolean=false
)
case class ProtoMessage(adapterName: String, classImpl: String, adapterImpl: String)

@compileTimeOnly("not expanded")
class schema extends scala.annotation.StaticAnnotation {
  inline def apply(defn: Any): Any = meta {
    val q"object $objectName { ..$stats }" = defn
    val messages: List[ProtoMessage] = stats.map{
      case q"..$mods trait ${Type.Name(messageName)} { ..$stats }" =>
        def idOpt(mods: Seq[Mod]): Option[Int] = mods match {
          case Seq() ⇒ None
          case Seq(mod"@Id(${Lit(id:Int)})") ⇒ Option(id)
        }
        val id: Option[Int] = idOpt(mods)
        val props: List[ProtoProp] = stats.map{
          case q"..$mods def ${Term.Name(propName)}: $tpe" ⇒
            val id = idOpt(mods).get
            val pt: ProtoType = tpe match {
              case t"Int" ⇒
                ProtoType(
                  s"if(prep_$propName != 0){ val item = prep_$propName; ",
                  "com.squareup.wire.ProtoAdapter.SINT32", "0", "Int",
                  isSystem=true
                )
              case t"Long" ⇒
                ProtoType(
                  s"if(prep_$propName != 0L){ val item = prep_$propName; ",
                  "com.squareup.wire.ProtoAdapter.SINT62", "0", "Long",
                  isSystem=true
                )
              case t"okio.ByteString" ⇒
                ProtoType(
                  s"if(prep_$propName.nonEmpty){ val item = prep_$propName; ",
                  "com.squareup.wire.ProtoAdapter.BYTES", "okio.ByteString.EMPRY", "okio.ByteString",
                  isSystem=true
                )
              case t"String" ⇒
                ProtoType(
                  s"if(prep_$propName.nonEmpty){ val item = prep_$propName; ",
                  "com.squareup.wire.ProtoAdapter.STRING", "\"\"", "String"
                )
              case t"Option[${Type.Name(name)}]" ⇒
                ProtoType(
                  s"if(prep_$propName.nonEmpty){ val item = prep_$propName.get; ",
                  s"${name}ProtoAdapter", "None", s"Option[$name]",
                  reduce=("Option(", ")")
                )
              case t"List[${Type.Name(name)}]" ⇒
                ProtoType(
                  s"prep_$propName.foreach{ item => ",
                  s"${name}ProtoAdapter", "Nil", s"List[$name]",
                  reduce=("", s":: prep_$propName"), resultFix=s"prep_$propName = prep_$propName.reversed"
                )
              case t"Option[BigDecimal] @scale(${Lit(scale:Int)})" ⇒
                val name = "BigDecimal"
                ProtoType(
                  s"if(prep_$propName.nonEmpty){ val item = prep_$propName.get; ",
                  s"${name}ProtoAdapter", "None", s"Option[$name]",
                  reduce=("Option(", ")")
                )
              /*
              //ProtoType("com.squareup.wire.ProtoAdapter.BOOL", "\"\"", "String")
              //String, Option[Boolean], Option[Int], Option[BigDecimal], Option[Instant], Option[$]
              */
            }
            ProtoProp(
                defArg = s"$propName: ${pt.resultType}",
                sizeStatement = s"${pt.encodeStatement} res += ${pt.serializerType}.encodedSizeWithTag($id, item)}",
                encodeStatement = s"${pt.encodeStatement} ${pt.serializerType}.encodeWithTag(writer, $id, item)}",
                initDecodeStatement = s"var prep_$propName: ${pt.resultType} = ${pt.empty}",
                decodeCase = s"case $id => prep_$propName = ${pt.reduce._1} ${pt.serializerType}.decode(reader) ${pt.reduce._2}",
                constructArg = s"prep_$propName",
                resultFix = if(pt.resultFix.nonEmpty) s"prep_$propName = ${pt.resultFix}" else "",
                isSystem = pt.isSystem
            )
        }.toList
        val classImpl = if(props.exists(_.isSystem)) "" else
          s"""case class ${messageName}Impl(${props.map(_.defArg).mkString(",")}) extends $messageName"""
        val struct = s"""${messageName}Impl(${props.map(_.constructArg).mkString(",")})"""
        val adapterImpl = s"""
          object ${messageName}ProtoAdapter extends com.squareup.wire.ProtoAdapter[$messageName](
            com.squareup.wire.FieldEncoding.LENGTH_DELIMITED,
            classOf[$messageName]
          ) ${id.map(_⇒"with ProtoAdapterWithId").getOrElse("")} {
            ${id.map(i⇒s"def id = $i").getOrElse("")}
            def encodedSize(value: $messageName): Int = {
              val $struct = value
              var res = 0;
              ${props.map(_.sizeStatement).mkString("\n")}
              res
            }
            def encode(writer: com.squareup.wire.ProtoWriter, value: $messageName) = {
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
        val res = ProtoMessage(s"${messageName}ProtoAdapter", classImpl, adapterImpl)
        println(res)
        res

    }.toList
    val res = q"""
      object $objectName {
        ..$stats;
        ..${messages.map(_.classImpl.parse[Stat].get)};
        ..${messages.map(_.adapterImpl.parse[Stat].get)};
        def adapters: List[com.squareup.wire.ProtoAdapter[_<:Object] with ProtoAdapterWithId] =
          List(..${messages.map(_.adapterName.parse[Term].get)})
      }"""
    println(res)
    res
  }
}
