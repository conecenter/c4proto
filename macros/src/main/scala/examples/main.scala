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

case class ProtoProp(id: Option[Int], name: String, tpe: ProtoType)
case class ProtoType(serializerType: String, empty: String, resultType: String)
case class ProtoMessage(id: Option[Int], name: String, props: List[ProtoProp])

@compileTimeOnly("not expanded")
class schema extends scala.annotation.StaticAnnotation {
  inline def apply(defn: Any): Any = meta {
    val q"object $objectName { ..$stats }" = defn
    val messages = stats.map{
      case q"..$mods trait ${Type.Name(messageName)} { ..$stats }" =>
        def idOpt(mods: Seq[Mod]): Option[Int] = mods match {
          case Seq() ⇒ None
          case Seq(mod"@Id(${Lit(id:Int)})") ⇒ Option(id)
        }
        val id: Option[Int] = idOpt(mods)
        val props = stats.map{
          case q"..$mods def ${Term.Name(propName)}: $tpe" ⇒
            val id = idOpt(mods)
            val pt: ProtoType = tpe match {
              case t"String" ⇒
                ProtoType("com.squareup.wire.ProtoAdapter.STRING", "\"\"", "String")
              case t"Option[${Type.Name(name)}]" ⇒
                ProtoType(s"${name}ProtoAdapter", "None", s"Option[$name]")
              case t"List[${Type.Name(name)}]" ⇒
                ProtoType(s"${name}ProtoAdapter", "Nil", s"List[$name]")
              case t"Option[BigDecimal] @scale(${Lit(scale:Int)})" ⇒
                val name = "BigDecimal"
                ProtoType(s"${name}ProtoAdapter", "None", s"Option[$name]")
/*
                //ProtoType("com.squareup.wire.ProtoAdapter.BOOL", "\"\"", "String")

              //String, Option[Boolean], Option[Int], Option[BigDecimal], Option[Instant], Option[$]
                // List[$]
              case q"${Type.Name(name)}" ⇒
                //println(4,name)
              case t"Option[${Type.Name(name)}]" ⇒
                //println(3,name)
              case t"Option[BigDecimal] @scale(${Lit(scale:Int)})" ⇒
                println(6,scale)*/
              /*case expr ⇒
                println(5,expr.structure)*/
            }
            ProtoProp(id, propName, pt)
        }.toList
        ProtoMessage(id, messageName, props)
    }.toList
    val adaptersImpl = messages.map{message⇒
      val adapterName = Term.Name(s"${message.name}ProtoAdapter")
      val (withId, idDef): (List[Name],List[Def]) = message.id
        .map(id⇒(ctor"ProtoAdapterWithId"::Nil,q"def id = ${Lit(id)}"::Nil))
        .getOrElse((Nil,Nil))
      val implTypeName = Type.Name(s"${message.name}Impl")
      val defArgs: List[Term.Param] = message.props.map{ prop ⇒
        param"${Term.Name(prop.name)}: ${Type.Name(prop.tpe.resultType)}"
      }
      val sizeStatements: List[Stat] = message.props.map { prop ⇒
        q"value.${Term.Name(prop.name)}.foreach(item => res += ${Term.Name(prop.tpe.serializerType)}.encodedSizeWithTag(${Lit(prop.id)}, item))"
      }
      val encodeStatements: List[Stat] = message.props.map { prop ⇒
        q"value.${Term.Name(prop.name)}.foreach(item => ${Term.Name(prop.tpe.serializerType)}.encodeWithTag(writer, ${Lit(prop.id)}, item))"
      }
      val initDecodeStatements: List[Stat] = message.props.map { prop ⇒
        s"var prop_${prop.name}: ${prop.tpe.resultType} = ${prop.tpe.empty}".parse[Stat].get
      }
      val decodeCases: List[Case] = message.props.map { prop ⇒
        val decoded = s"${prop.tpe.serializerType}.decode(reader)"
        /*"\n                    case $$_{id} => prep_$$_{name} = $$_{serde}.decode(reader) :: prep_$$_{name}" :
                "\n                    case $$_{id} => prep_$$_{name} = Option($$_{serde}.decode(reader))"
        */
        s"case ...".parse[Case].get
      }
      val constructArgs: List[Term.Arg] = message.props.map { prop ⇒
        s"prop_${prop.name}".parse[Term.Arg].get
      }
      val messageName = Type.Name(message.name) //${Lit(message.id)}
      val implTermName = Term.Name(s"${message.name}Impl")
      (adapterName,List(
        q"""case class $implTypeName(..$defArgs) extends ${Ctor.Name(message.name)} {}""",
        q"""
          object $adapterName extends com.squareup.wire.ProtoAdapter[$messageName](
            com.squareup.wire.FieldEncoding.LENGTH_DELIMITED,
            classOf[$messageName]
          ) with ..$withId {
            ..$idDef;
            def encodedSize(value: $messageName): Int = {
              var res = 0;
              ..$sizeStatements
              res
            }
            def encode(writer: com.squareup.wire.ProtoWriter, value: $messageName) = {
              ..$encodeStatements
            }
            def decode(reader: com.squareup.wire.ProtoReader) = {
              ..$initDecodeStatements;
              val token = reader.beginMessage();
              var done = false;
              while(!done) reader.nextTag() match {
                case -1 => done = true
                ..case $decodeCases
                case _ => reader.peekFieldEncoding.rawProtoAdapter.decode(reader)
              }
              reader.endMessage(token)
              $implTermName(..$constructArgs)
            }
          }
        """
      ))
    }
    val adaptersList = adaptersImpl.map{ case(adapterName,classes)⇒adapterName} //.mkString(",")
    val adaptersDef = q"def adapters: List[com.squareup.wire.ProtoAdapter[_<:Object] with ProtoAdapterWithId] = List(..$adaptersList)"
    val res = q"object $objectName { ..$stats; ..${adaptersImpl.flatMap(_._2)}; $adaptersDef }"
    println(res)
    res
  }
}
