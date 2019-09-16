
package ee.cone.c4generator

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.meta.Term.Name
import scala.meta._

import scala.collection.immutable.Seq

object ComponentsGenerator extends Generator {
  //private def isC4Component = { case mod"@c4component" ⇒ true }
  def get: Get = {
    case (q"..$cMods class $tname[..$tparams] ..$ctorMods (...$paramsList) extends ..$ext { ..$stats }", fileName)
      if cMods.collectFirst{ case mod"@c4component" ⇒ true }.nonEmpty ⇒
      val Type.Name(tp) = tname
      val abstractType: Type = ext match {
        case init"${t@Type.Name(_)}(...$a)" :: _ ⇒
          val clArgs = a.flatten.collect{ case q"classOf[$a]" ⇒ a }
          if(clArgs.nonEmpty) Type.Apply(t,clArgs) else t
        case init"$t(...$_)" :: _ ⇒ t
      }
      val key = getTypeKey(abstractType).parse[Term].get
      val list = for{
        params ← paramsList.toList
      } yield for {
        param"..$mods ${Name(name)}: ${Some(tpe)} = $expropt" ← params
        r ← if(expropt.nonEmpty) None
        else Option((Option((tpe,name)),q"${Term.Name(name)}.asInstanceOf[$tpe]"))
      } yield r
      val args = for { args ← list } yield for { (_,a) ← args } yield a
      val caseSeq = for { (o,_) ← list.flatten; (_,a) ← o } yield Pat.Var(Term.Name(a))
      val depSeq = for { (o,_) ← list.flatten; (a,_) ← o } yield getTypeKey(a).parse[Term].get
      val dep = q"scala.collection.immutable.Seq(...${List(depSeq)})"
      val objName = Term.Name(s"${tp}Component")
      val concrete = q"new ${Type.Name(tp)}(...$args)"
      Seq(GeneratedCode(q"object $objName extends Component($key,$dep,{ case Seq(..$caseSeq) => $concrete })".syntax))
  }
  def getTypeKey(t: Type): String = {
    t match {
      case t"$tpe[..$tpesnel]" => s"""ee.cone.c4proto.TypeKey(classOf[$tpe[${tpesnel.map(_ ⇒ "_").mkString(", ")}]].getName, "$tpe", ${tpesnel.map(getTypeKey)})"""
      case t"$tpe" ⇒ s"""ee.cone.c4proto.TypeKey(classOf[$tpe].getName, "$tpe", Nil)"""
    }
  }

}


case class ProtoProp(
  id: Int, name: String, argType: String, metaProp: String,
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

  def getCat(origType: String): List[String] = {
    if (origType.charAt(1) == '_') List(s"ee.cone.c4proto.${origType.charAt(0)}_Cat") else Nil
      // throw new Exception(s"Invalid name for Orig: $origType, should start with 'W_' or unsupported orig type")
  }

  def get: Get = { case (code@q"@protocol(...$exprss) object ${objectNameNode@Term.Name(objectName)} extends ..$ext { ..$stats }", fileName) ⇒ Util.unBase(objectName,objectNameNode.pos.end){ objectName ⇒

      //println(t.structure)

    val args = parseArgs(exprss)

    val messages: List[ProtoMessage] = stats.flatMap{
      case q"import ..$i" ⇒ None
      case q"..$mods case class ${Type.Name(messageName)} ( ..$params )" =>
        val resultType = messageName //?
        val factoryName = messageName //?
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

            val id = fieldProps.id.get
            val metaProp = s"""ee.cone.c4proto.MetaProp($id,"$propName",${deOpt(fieldProps.shortName)},"$tp", ${ComponentsGenerator.getTypeKey(tp)})"""
            val lens = if(protoMods.genLens) Option(getLens(objectName, resultType, id, propName, s"$tp", fieldProps)) else None
            ProtoProp(id, propName, s"$tp", metaProp, lens)
          case t: Tree ⇒
            Utils.parseError(t, "protocol", fileName)
        }.toList
        val struct = s"""${factoryName}(${props.map(p⇒s"prep_${p.name}").mkString(",")})"""
        val statements = List(
          s"""type ${resultType} = ${objectName}Base.${resultType}""",
          s"""val ${factoryName} = ${objectName}Base.${factoryName}""",
          s"""
          @c4component class ${resultType}ProtoAdapter(
            ${props.map(p ⇒ s"adapter_${p.name}: ArgAdapter[${p.argType}]").mkString(",\n")}
          ) extends ProtoAdapter[$resultType](
            com.squareup.wire.FieldEncoding.LENGTH_DELIMITED,
            classOf[$resultType]
          ) with ee.cone.c4proto.HasId {
            def id = ${protoMods.id.getOrElse("throw new Exception")}
            def hasId = ${protoMods.id.nonEmpty}
            def className = classOf[$resultType].getName
            def encodedSize(value: $resultType): Int = {
              val $struct = value
              0 ${props.map(p⇒s" + adapter_${p.name}.encodedSizeWithTag(${p.id}, prep_${p.name})").mkString}
            }
            def encode(writer: com.squareup.wire.ProtoWriter, value: $resultType) = {
              val $struct = value
              ${props.map(p⇒s"adapter_${p.name}.encodeWithTag(writer,${p.id}, prep_${p.name})").mkString("; ")}
            }
            @annotation.tailrec private def decodeMore(
              reader: com.squareup.wire.ProtoReader${props.map(p⇒s", prep_${p.name}: ${p.argType}").mkString}
            ): ${resultType} = reader.nextTag() match {
              case -1 =>
                ${factoryName}(${props.map(p⇒s"adapter_${p.name}.decodeFix(prep_${p.name})").mkString(", ")})
              ${props.map(outerProp⇒s"case ${outerProp.id} => decodeMore(reader${
                props.map(p ⇒
                  if(p eq outerProp)
                    s", adapter_${p.name}.decodeReduce(reader,prep_${p.name})"
                  else s", prep_${p.name}"
                ).mkString
              })").mkString("\n")}
              case _ =>
                reader.peekFieldEncoding.rawProtoAdapter.decode(reader)
                decodeMore(reader${props.map(p ⇒ s", prep_${p.name}").mkString})
            }
            def decode(reader: com.squareup.wire.ProtoReader): ${resultType} = {
              val token = reader.beginMessage();
              val res = decodeMore(reader${props.map(p⇒s", adapter_${p.name}.defaultValue").mkString})
              reader.endMessage(token)
              res
            }
            def props = List(${props.map(_.metaProp).mkString(",")})

            // extra
            val ${messageName}_categories = List(${(getCat(resultType) ::: protoMods.category).mkString(", ")}).distinct
            def categories = ${messageName}_categories
            def cl = classOf[$resultType]
            def shortName = ${deOpt(protoMods.shortName)}
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
    val messageStats = messages.flatMap(_.statements).map(_.parse[Stat].get)
    val components = for {
      s <- messageStats
      rs <- ComponentsGenerator.get.lift((s,fileName)).toList
      r <- rs
    } yield r.content.parse[Stat].get
    val imports = stats.collect{ case s@q"import ..$i" ⇒ s }
    val res = q"""
      object ${Term.Name(objectName)} extends Protocol {
        import ee.cone.c4proto.ArgAdapter
        import com.squareup.wire.ProtoAdapter
        ..$imports;
        ..$messageStats;
        ..$components;
        override def components = List(..${messages.map(m⇒s"${m.adapterName}Component".parse[Term].get)})
      }"""
    //println(res)
    //Util.comment(code)(cont) +
    GeneratedCode(res.syntax) :: messages.map(_.lenses).map(GeneratedCode.apply)
  }}
}

