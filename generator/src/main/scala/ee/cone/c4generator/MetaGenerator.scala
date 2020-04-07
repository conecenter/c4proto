package ee.cone.c4generator

import scala.collection.immutable.Seq
import scala.meta.Term.Name
import scala.meta._

case class OrigMetaAnnotations(
  id: Option[String] = None,
  cats: List[String] = Nil,
  replaceBy: Option[String] = None,
  shortName: Option[String] = None,
  metaAttrs: List[String] = Nil,
  anns: List[String] = Nil
)

case class FieldMetaAnnotations(
  id: Option[String] = None,
  shortName: Option[String] = None,
  metaAttrs: List[String] = Nil,
  anns: List[String] = Nil
)

class MetaGenerator(statTransformers: List[ProtocolStatsTransformer]) extends Generator {
  def get(parseContext: ParseContext): List[Generated] = {
    val result = parseContext.stats.flatMap {
      case q"@protocol(...$exprss) object ${objectNameNode@Term.Name(objectName)} extends ..$ext { ..$stats }" =>
        val c4ann = if (exprss.isEmpty) "@c4" else mod"@c4(...$exprss)".syntax
        val transformers = statTransformers.map(_.transform(parseContext))
        val preparedStats = transformers.foldLeft(stats.toList) { (list, transformer) => transformer(list) }
        getMeta(parseContext, objectName, preparedStats, c4ann)
      case _ => Nil
    }
    if (result.nonEmpty)
      GeneratedImport("import ee.cone.c4actor.Types._") ::
      GeneratedImport("import ee.cone.c4actor.{AbstractMetaAttr, OrigMeta, GeneralOrigMeta, FieldMeta}") ::
        GeneratedImport("import ee.cone.c4proto.DataCategory") ::
        GeneratedImport("import ee.cone.c4di._") ::
        result
    else Nil
  }

  def parseArgs: Seq[Seq[Term]] => List[String] =
    _.flatMap(_.collect { case q"${Name(name: String)}" => name }).toList

  def parseArgsWithApply: Seq[Seq[Term]] => List[String] =
    _.flatMap(_.map(_.toString())).toList

  def wrapAsString: String => String = in => s"""\"\"\"$in\"\"\""""

  def deOpt: Option[String] => String = {
    case None => "None"
    case Some(a) => s"""Some("$a")"""
  }

  def getCat(origType: String, idOpt: Option[_]): List[String] = {
    val cat =
      if (origType.charAt(1) == '_') origType.charAt(0)
      else if(idOpt.isEmpty) 'N'
      else throw new Exception(s"Invalid name for Orig: $origType, should start with 'W_' or unsupported orig type")
    List(s"ee.cone.c4proto.${cat}_Cat")
  }

  def getMeta(parseContext: ParseContext, objectName: String, stats: List[Stat], c4ann: String): Seq[Generated] = {
    val classes = Util.matchClass(stats)
    classes.flatMap { classDef =>
      val annotations = classDef.mods.foldLeft(OrigMetaAnnotations())((curr, mod) => mod match {
        case mod"case" => curr
        case mod"@Cat(...${exprss: Seq[Seq[Term]]})" =>
          curr.copy(cats = parseArgs(exprss) ::: curr.cats)
        case mod"@Id(${value@Lit(id: Int)})" =>
          if (curr.id.isEmpty) curr.copy(id = Some(value.syntax))
          else Utils.parseError(classDef.nameNode, parseContext, "Orig has multiple @Id")
        case mod"@replaceBy[$tpe](${Term.Name(_)})" =>
          curr.copy(replaceBy = Some(ComponentsGenerator.getTypeKey(tpe, None)))
        case mod"@ShortName(${Lit(shortName: String)})" =>
          if (curr.shortName.isEmpty) curr.copy(shortName = Some(shortName))
          else Utils.parseError(classDef.nameNode, parseContext, "Orig has multiple @ShortName")
        case mod"@Meta(...${exprss: Seq[Seq[Term]]})" =>
          curr.copy(metaAttrs = parseArgsWithApply(exprss) ::: curr.metaAttrs)
        case mod"@$annot" =>
          curr.copy(anns = wrapAsString(annot.init.syntax) :: curr.anns)
      }
      )
      val origTypeKey = ComponentsGenerator.getTypeKey(classDef.nameNode, None)
      val inherits = classDef.ext.map{
        case init"$tpe(...$_)" => ComponentsGenerator.getTypeKey(tpe, None)
        case t => throw new Exception(t.structure)
      }
      val Seq(fields) = classDef.params
      val fieldsMeta = fields.map{
        case param"..$mods ${Term.Name(propName)}: $tpeopt = $v" =>
          val fieldAnnotations = mods.foldLeft(FieldMetaAnnotations())((curr, mod) => mod match {
            case mod"@Id(${value@Lit(id:Int)})" =>
              if (curr.id.isEmpty) curr.copy(id = Some(value.syntax))
              else Utils.parseError(classDef.nameNode, parseContext, "Field has multiple @Id")
            case mod"@ShortName($name)" =>
              if (curr.shortName.isEmpty) curr.copy(shortName = Some(name.syntax))
              else Utils.parseError(classDef.nameNode, parseContext, "Field has multiple @ShortName")
            case mod"@Meta(...$exprss)" =>
              curr.copy(metaAttrs = parseArgsWithApply(exprss) ::: curr.metaAttrs)
            case mod"@$annot" =>
              curr.copy(anns = wrapAsString(annot.init.syntax) :: curr.anns)
            case _ => curr
          })
          val Some(tpe) = tpeopt
          val fieldTypeKey = ComponentsGenerator.getTypeKey(tpe, None)
          import fieldAnnotations._
          s"""    FieldMeta(
             |      id = ${id.get},
             |      name = ${wrapAsString(propName)},
             |      shortName = $shortName,
             |      typeKey = $fieldTypeKey,
             |      metaAttrs = $metaAttrs,
             |      annotations = $anns
             |    )""".stripMargin
        case t: Tree =>
          Utils.parseError(t, parseContext, "Invalid field defenition")
      }
      import annotations._
      GeneratedImport(s"import $objectName._") ::
        GeneratedCode(
          s"""$c4ann class ${classDef.name}OrigMeta extends OrigMeta[${classDef.name}] {
             |  val id: Option[TypeId] = $id
             |  val categories: List[DataCategory] = ${getCat(classDef.name,id) ::: cats}.distinct
             |  val fieldsMeta: List[FieldMeta] = List(
             |${fieldsMeta.mkString(",\n")}
             |  )
             |  val shortName: Option[String] = ${deOpt(shortName)}
             |  val typeKey: TypeKey = $origTypeKey
             |  val replaces: Option[TypeKey] = $replaceBy
             |  val metaAttrs: List[AbstractMetaAttr] = $metaAttrs
             |  val annotations: List[String] = $anns
             |  val inherits: List[TypeKey] = $inherits
             |}
             |""".stripMargin
        ) :: Nil
    }
  }
}
