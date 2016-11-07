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
      val messageName = Type.Name(message.name)//${Lit(message.id)}
      q"""object $adapterName extends com.squareup.wire.ProtoAdapter[$messageName](
        com.squareup.wire.FieldEncoding.LENGTH_DELIMITED,
        classOf[$messageName]
        )
        with ProtoAdapterWithId {
          def id = ???
          def encodedSize(value: $messageName): Int = ???
          def encode(writer: com.squareup.wire.ProtoWriter, value: $messageName) = ???
          def decode(reader: com.squareup.wire.ProtoReader) = ???

        }"""
    }
    val adaptersList = messages.map(message⇒Term.Name(s"${message.name}ProtoAdapter")) //.mkString(",")
    val adaptersDef = q"def adapters: List[com.squareup.wire.ProtoAdapter[_<:Object] with ProtoAdapterWithId] = List(..$adaptersList)"
    q"object $objectName { ..$stats; ..$adaptersImpl; $adaptersDef }"
  }
}
