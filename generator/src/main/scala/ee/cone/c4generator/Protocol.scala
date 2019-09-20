
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
      val key = getTypeKey(abstractType)
      val list = for{
        params ← paramsList.toList
      } yield for {
        param"..$mods ${Name(name)}: ${Some(tpe)} = $expropt" ← params
        r ← if(expropt.nonEmpty) None
        else Option((Option((tpe,name)),q"${Term.Name(name)}.asInstanceOf[$tpe]"))
      } yield r
      val args = for { args ← list } yield for { (_,a) ← args } yield a
      val caseSeq = for {(o,_) ← list.flatten; (_,a) ← o} yield a
      val depSeq = for { (o,_) ← list.flatten; (a,_) ← o } yield getTypeKey(a)
      val objName = Term.Name(s"${tp}Component")
      val concrete = q"new ${Type.Name(tp)}(...$args)".syntax
      List(GeneratedComponent(s"link$tp :: ",
        s"""\n  private def out$tp = $key""" +
        s"""\n  private def in$tp = """ +
        depSeq.map(s⇒s"\n    $s ::").mkString +
        s"""\n    Nil""" +
        s"""\n  private def create$tp(args: scala.collection.immutable.Seq[Object]): $tp = {""" +
        s"""\n    val Seq(${caseSeq.mkString(",")}) = args;""" +
        s"""\n    $concrete""" +
        s"""\n  }""" +
        s"""\n  private lazy val link$tp = new Component(out$tp,in$tp,create$tp)"""
      ))
  }
  def getTypeKey(t: Type): String = {
    t match {
      case t"$tpe[..$tpesnel]" =>
        val tArgs = tpesnel.map(_ ⇒ "_").mkString(", ")
        val args = tpesnel.flatMap{ case t"_" ⇒ Nil case t ⇒ List(getTypeKey(t)) }
        s"""TypeKey(classOf[$tpe[$tArgs]].getName, "$tpe", $args)"""
      case t"$tpe" ⇒
        s"""TypeKey(classOf[$tpe].getName, "$tpe", Nil)"""
    }
  }
  def join(comps: Seq[GeneratedComponent]): String =
    comps.map(_.cContent).mkString +
    "\n  def components = " +
    comps.map(c ⇒ s"\n    ${c.name}").mkString +
    "\n    Nil"

}


case class ProtoProp(id: Int, name: String, argType: String, metaProp: String)
case class ProtoType(
  encodeStatement: (String,String), serializerType: String, empty: String, resultType: String,
  resultFix: String="", reduce: (String,String)=("","")
)

case class ProtoMods(
  resultType: String,
  factoryName: String,
  id: Option[Int]=None,
  category: List[String],
  shortName: Option[String] = None
)
case class FieldMods(id: Option[Int]=None, shortName: Option[String] = None)

object ProtocolGenerator extends Generator {
  def parseArgs: Seq[Seq[Term]] ⇒ List[String] =
    _.flatMap(_.collect{case q"${Name(name:String)}" ⇒ name}).toList

  def deOpt: Option[String] ⇒ String = {
    case None ⇒ "None"
    case Some(a) ⇒ s"""Some("$a")"""
  }

  def getCat(origType: String, idOpt: Option[_]): List[String] = {
    val cat =
      if (origType.charAt(1) == '_') origType.charAt(0)
      else if(idOpt.isEmpty) 'N'
      else throw new Exception(s"Invalid name for Orig: $origType, should start with 'W_' or unsupported orig type")
    List(s"ee.cone.c4proto.${cat}_Cat")
  }

  def get: Get = { case (code@q"@protocol(...$exprss) object ${objectNameNode@Term.Name(objectName)} extends ..$ext { ..$stats }", fileName) ⇒ Util.unBase(objectName,objectNameNode.pos.end){ objectName ⇒
      //println(t.structure)
    val args = parseArgs(exprss)
    val protoGenerated: List[Generated] = stats.flatMap{
      case c@q"import ..$i" ⇒ List(GeneratedImport(s"\n  $c"))
      case q"sealed trait ${Type.Name(tp)}" ⇒
        List(
          GeneratedTraitDef(tp),
          GeneratedCode(s"\n  @c4component class ${tp}ProtoAdapterHolder(inner: ProtoAdapterHolder[Product]) extends ProtoAdapterHolder[$tp](inner.asInstanceOf[ProtoAdapter[$tp]])")
        )
      case q"..$mods case class ${Type.Name(messageName)} ( ..$params ) extends ..$ext" =>
        val protoMods = mods./:(ProtoMods(messageName, messageName, category = args))((pMods,mod)⇒ mod match {
          case mod"@Cat(...$exprss)" ⇒
            val old = pMods.category
            pMods.copy(category = parseArgs(exprss) ::: old)
          case mod"@Id(${Lit(id:Int)})" if pMods.id.isEmpty ⇒
            pMods.copy(id=Option(id))
          case mod"@replaceBy[${Type.Name(rt)}](${Term.Name(fact)})" ⇒
            pMods.copy(resultType=rt, factoryName=fact)
          case mod"@ShortName(${Lit(shortName:String)})" if pMods.shortName.isEmpty ⇒
            pMods.copy(shortName=Option(shortName))
          case mod"@GenLens" ⇒ pMods
          case mod"@deprecated(...$notes)" ⇒ pMods
          case t: Tree ⇒ Utils.parseError(t, "protocol", fileName)
        })
        import protoMods.{resultType,factoryName}
        val props: List[ProtoProp] = params.map{
          case param"..$mods ${Term.Name(propName)}: $tpeopt = $v" ⇒
            val fieldProps = mods./:(FieldMods())((fMods, mod) ⇒ mod match {
              case mod"@Id(${Lit(id:Int)})" ⇒
                fMods.copy(id = Option(id))
              case mod"@ShortName(${Lit(shortName:String)})" ⇒
                fMods.copy(shortName = Option(shortName))
              case mod"@deprecated(...$notes)" ⇒ fMods
              case mod"@Meta(...$exprss)" ⇒ fMods
              case t: Tree ⇒
                Utils.parseError(t, "protocol", fileName)
            })
            val tp = tpeopt.asInstanceOf[Option[Type]].get
            val id = fieldProps.id.get
            val metaProp = s"""ee.cone.c4proto.MetaProp($id,"$propName",${deOpt(fieldProps.shortName)},"$tp", ${ComponentsGenerator.getTypeKey(tp)})"""
            ProtoProp(id, propName, s"$tp", metaProp)
          case t: Tree ⇒
            Utils.parseError(t, "protocol", fileName)
        }.toList
        val struct = s"""${factoryName}(${props.map(p⇒s"prep_${p.name}").mkString(",")})"""
        ext.map{ case init"${Type.Name(tn)}" ⇒ GeneratedTraitUsage(tn) } ::: List(
          GeneratedCode(s"""\n  type ${resultType} = ${objectName}Base.${resultType}"""),
          GeneratedCode(s"""\n  val ${factoryName} = ${objectName}Base.${factoryName}"""),
          GeneratedCode(s"""
  @c4component class ${resultType}ProtoAdapter(
    ${props.map(p ⇒ s"\n    adapter_${p.name}: ArgAdapter[${p.argType}]").mkString(",")}
  ) extends ProtoAdapter[$resultType](
    com.squareup.wire.FieldEncoding.LENGTH_DELIMITED,
    classOf[$resultType]
  ) with ee.cone.c4proto.HasId {
    def id = ${protoMods.id.getOrElse("throw new Exception")}
    def hasId = ${protoMods.id.nonEmpty}
    def className = classOf[$resultType].getName
    def encodedSize(value: $resultType): Int = {
      val $struct = value
      (0
        ${props.map(p⇒s"\n        + adapter_${p.name}.encodedSizeWithTag(${p.id}, prep_${p.name})").mkString}
      )
    }
    def encode(writer: com.squareup.wire.ProtoWriter, value: $resultType) = {
      val $struct = value
      ${props.map(p⇒s"\n      adapter_${p.name}.encodeWithTag(writer,${p.id}, prep_${p.name})").mkString}
    }
    @annotation.tailrec private def decodeMore(
      reader: com.squareup.wire.ProtoReader${props.map(p⇒s", prep_${p.name}: ${p.argType}").mkString}
    ): ${resultType} = reader.nextTag() match {
      case -1 =>
        ${factoryName}(${props.map(p⇒s"adapter_${p.name}.decodeFix(prep_${p.name})").mkString(", ")})
      ${props.map(outerProp⇒s"\n      case ${outerProp.id} => decodeMore(reader${
        props.map(p ⇒
          if(p eq outerProp)
            s", adapter_${p.name}.decodeReduce(reader,prep_${p.name})"
          else s", prep_${p.name}"
        ).mkString
      })").mkString}
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
    def cl = classOf[$resultType]
    lazy val categories = List(${(getCat(resultType,protoMods.id) ::: protoMods.category).mkString(", ")}).distinct
    def shortName = ${deOpt(protoMods.shortName)}
  }
        """)
        )
    }.toList
    //
    val traitDefs = protoGenerated.collect{ case m: GeneratedTraitDef ⇒ m.name }.toSet
    val traitUses = protoGenerated.collect{ case m: GeneratedTraitUsage ⇒ m.name }.toSet
    val traitIllegal = traitUses -- traitDefs
    if(traitIllegal.nonEmpty) throw new Exception(s"can not extend from non-local traits $traitIllegal")
    //
    val imports = protoGenerated.collect{ case m: GeneratedImport ⇒ m.content }
    val messageStats = protoGenerated.collect{ case m: GeneratedCode ⇒ m.content }
    val components = (for {
      s <- messageStats
      rs <- ComponentsGenerator.get.lift((s.parse[Stat].get,fileName)).toList
      r <- rs
    } yield r).collect{ case c: GeneratedComponent ⇒  c }
    val comp = ComponentsGenerator.join(components)
    val res = s"""
object $objectName extends Protocol {
  import ee.cone.c4proto._
  import com.squareup.wire.ProtoAdapter
  ${imports.mkString}${messageStats.mkString}$comp
}"""
    //println(res)
    //Util.comment(code)(cont) +
    List(GeneratedComponent(s"$objectName.components ::: ",""),GeneratedCode(res))
  }}
}
