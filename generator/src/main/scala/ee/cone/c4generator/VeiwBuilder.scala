package ee.cone.c4generator

import scala.collection.immutable.Seq
import scala.meta._

/*
val (tp,meta) = tpe.get match {
  case t"$tp @meta(..$ann)" => (tp,ann)
  case a => (a,Nil)
}
println(meta,meta.map(_.getClass))*/

case class OrigInfo(protocolName: String, origType: String, origId: Long, fields: List[FieldInfo])

case class FieldInfo(fieldId: Long, fieldName: String, fieldType: String, meta: List[String])

object ViewBuilderGenerator extends Generator {
  def get(parseContext: ParseContext): List[Generated] = parseContext.stats.flatMap{
    case code@q"@protocol(...$exprss) object ${objectNameNode@Term.Name(objectName)} extends ..$ext { ..$stats }" =>
      Util.unBase(objectName, objectNameNode.pos.end) { objectName =>
        val origs: List[OrigInfo] = stats.collect {
          case q"..$mods case class ${Type.Name(messageName)} ( ..$params ) extends ..$ext"
            if mods.collectFirst { case mod"@Getters" => true }.nonEmpty =>
            val origId = mods.collectFirst { case mod"@Id(${Lit(id: Int)})" => id }.get
            val resultType = messageName // simplification
            val fields = params.map {
              case param"..$mods ${Term.Name(propName)}: $tpeopt = $v" =>
                val Seq(id) = mods.collect { case mod"@Id(${Lit(id: Int)})" => id }
                val tp = tpeopt.asInstanceOf[Option[Type]].get
                val meta = mods.collect { case mod"@Meta(...$exprss)" => parseArgsWithApply(exprss) }.flatten.toList
                FieldInfo(id, propName, s"$tp", meta)
              case t: Tree =>
                Utils.parseError(t, parseContext)
            }
            OrigInfo(objectName, resultType, origId, fields.toList)
        }.toList
        if (origs.nonEmpty)
          Seq(
            GeneratedImport("ee.cone.core.c4security.views._newvb.{ProdAttrSetter, SrcIdProdAttrSetter, ProdAttrGetter, Descriptor, OrigIdAttr}"),
            GeneratedImport("ee.cone.core.c4security.views._newvb._api.{ProdSetters, ProdGettersApp, ProdSettersApp}"),
            GeneratedImport("ee.cone.core.c4security.views._newvb._assemble.OrigToSession"),
            GeneratedImport("ee.cone.c4actor.{IdMetaAttr, NameMetaAttr, AssemblesApp}"),
            GeneratedImport("ee.cone.c4assemble.Assemble"),
            GeneratedCode("\n" +
            s"""trait ${objectName}ViewBuilderApp
               |  extends ProdGettersApp
               |    with ProdSettersApp
               |    with AssemblesApp {
               |  override def getters: List[ProdAttrGetter[_ <: Product, _]] = ${gettersName(origs)} ::: super.getters
               |  override def prodSetters: List[ProdSetters[_ <: Product]] = ${settersName(origs)} :: super.prodSetters
               |  override def assembles: List[Assemble] = ${assemblesName(origs)} :: super.assembles
               |}
               |""".stripMargin + "\n\n" +
            getGetters(origs) + "\n\n" +
            getSetters(origs)
          )
          )
        else Seq.empty
      }
    case _ => Nil
  }

  def fullOrigName(info: OrigInfo): String =
    s"${info.protocolName}.${info.origType}"

  def toLong(number: Long): String =
    s"${number}L"

  def gettersName(infos: List[OrigInfo]): String =
    infos.map(info => s"${info.origType}Getters.all").mkString(" ::: ")

  def settersName(infos: List[OrigInfo]): String =
    infos.map(info => s"${info.origType}Setters.orig_setter").mkString(" :: ")

  def assemblesName(infos: List[OrigInfo]): String =
    infos.map(info => s"new OrigToSession(classOf[${fullOrigName(info)}], ${toLong(info.origId)})").mkString(" :: ")

  def parseArgsWithApply: Seq[Seq[Term]] => List[String] =
    _.flatMap(_.map(_.toString())).toList

  def getGetters(infos: List[OrigInfo]): String =
    infos.map(info =>
      s"""object ${info.origType}Getters {
         |${info.fields.map(field => getGetter(info, field)).mkString("\n")}
         |  val all = List(${info.fields.map(_.fieldName).mkString(", ")})
         |}""".stripMargin
    ).mkString("\n\n")

  def getGetter(origInfo: OrigInfo, field: FieldInfo): String =
    s"""  val ${field.fieldName}: ProdAttrGetter[${fullOrigName(origInfo)}, ${field.fieldType}] =
       |    ProdAttrGetter(Descriptor(
       |      OrigIdAttr(${toLong(origInfo.origId)}),
       |      IdMetaAttr(${toLong(field.fieldId)}),
       |      NameMetaAttr("${fullOrigName(origInfo)}.${field.fieldName}"),
       |      ${if (field.meta.isEmpty) "" else field.meta.mkString(",\n      ", ",\n      ", "")}
       |    ))(_.${field.fieldName})
       |""".stripMargin

  def getSetters(infos: List[OrigInfo]): String =
    infos.map(info =>
      s"""object ${info.origType}Setters {
         |${getSetter("SrcId", info, info.fields.head)}
         |${info.fields.tail.map(field => getSetter("", info, field)).mkString("\n")}
         |  val orig_setter = new ProdSetters[${fullOrigName(info)}](
         |    ${toLong(info.origId)},
         |    ${info.fields.head.fieldName},
         |    List(${info.fields.tail.map(_.fieldName).mkString(", ")})
         |  )
         |}""".stripMargin
    ).mkString("\n\n")

  def getSetter(prefix: String, origInfo: OrigInfo, field: FieldInfo): String =
    s"""  val ${field.fieldName}: ${prefix}ProdAttrSetter[${origInfo.protocolName}.${origInfo.origType}, ${field.fieldType}] =
       |    ${prefix}ProdAttrSetter(
       |      ${toLong(origInfo.origId)}, ${toLong(field.fieldId)}
       |    )(v => _.copy(${field.fieldName} = v))
       |""".stripMargin
}
