
package ee.cone.c4generator

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.meta.Term.Name
import scala.meta._
import scala.collection.immutable.Seq

case class ProtoProp(id: Int, name: String, argType: String)

case class ProtoMods(
  resultType: String,
  factoryName: String
)
case class FieldMods(id: Option[Int]=None, shortName: Option[String] = None)

trait ProtocolStatsTransformer {
  def transform(parseContext: ParseContext): List[Stat] => List[Stat]
}

class ProtocolGenerator(statTransformers: List[ProtocolStatsTransformer]) extends Generator {
  def parseArgs: Seq[Seq[Term]] => List[String] =
    _.flatMap(_.collect{case q"${Name(name:String)}" => name}).toList

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

  def get(parseContext: ParseContext): List[Generated] = parseContext.stats.flatMap{
    case q"@protocol(...$exprss) object ${objectNameNode@Term.Name(objectName)} extends ..$ext { ..$stats }" =>
      val c4ann = if (exprss.isEmpty) "@c4" else mod"@c4(...$exprss)".syntax
      /* val app = if(exprss.isEmpty)
        ComponentsGenerator.pkgNameToAppId(parseContext.pkg,"HasId")
      else ComponentsGenerator.annArgToStr(exprss).get */
      val transformers = statTransformers.map(_.transform(parseContext))
      val preparedStats = transformers.foldLeft(stats.toList){(list, transformer) => transformer(list)}
      getProtocol(parseContext, objectName, preparedStats, c4ann)
    case _ => Nil
  }
  def getAdapter(parseContext: ParseContext, objectName: String, cl: ParsedClass, c4ann: String): List[Generated] = {
    val protoMods = cl.mods.foldLeft(ProtoMods(cl.name, cl.name))((pMods,mod)=> mod match {
      case mod"@replaceBy[$rtExpr]($factExpr)" =>
        val Type.Name(rt) = rtExpr
        val Term.Name(fact) = factExpr
        pMods.copy(resultType=rt, factoryName=fact)
      case _ => pMods
    })
    import protoMods.{resultType,factoryName}
    val Seq(params) = cl.params
    val props: List[ProtoProp] = params.map{
      case param@param"..$mods ${Term.Name(propName)}: $tpeopt = $v" =>
        val tp = tpeopt.asInstanceOf[Option[Type]].get
        val id = mods.foldLeft[Option[Int]](None)((idOpt, mod) => mod match {
          case mod"@Id(${Lit(id:Int)})" =>
            if (idOpt.isEmpty) Option(id)
            else Utils.parseError(param, parseContext, "Orig field with multiple @Id")
          case _ => idOpt
        }).getOrElse(Utils.parseError(param, parseContext, "Orig field without @Id"))
        ProtoProp(id, propName, s"$tp")
      case t: Tree =>
        Utils.parseError(t, parseContext)
    }.toList
    val struct = s"""${factoryName}(${props.map(p=>s"prep_${p.name}").mkString(",")})"""
    val traitUsages = cl.ext.map{
      case init"${Type.Name(tn)}(...$_)" => GeneratedTraitUsage(tn)
      case t => throw new Exception(t.structure)
    }
    traitUsages ::: List(
      GeneratedImport(s"""\nimport $objectName.$resultType"""),
      GeneratedCode(s"""
$c4ann final class ${cl.name}ProtoAdapter(
  val protoOrigMeta: OrigMeta[${cl.name}],
  ${props.map(p => s"\n    adapter_${p.name}: ArgAdapter[${p.argType}]").mkString(",")}
) extends ProtoAdapter[$resultType](FieldEncoding.LENGTH_DELIMITED,classOf[$resultType]) with HasId {
  def redact(value: $resultType): $resultType = value
  def encodedSize(value: $resultType): Int = {
    val $struct = value
    (0
      ${props.map(p=>s"\n        + adapter_${p.name}.encodedSizeWithTag(${p.id}, prep_${p.name})").mkString}
    )
  }
  def encode(writer: ProtoWriter, value: $resultType) = {
    val $struct = value
    ${props.map(p=>s"\n      adapter_${p.name}.encodeWithTag(writer,${p.id}, prep_${p.name})").mkString}
  }
  @annotation.tailrec private def decodeMore(
    reader: ProtoReader${props.map(p=>s", prep_${p.name}: ${p.argType}").mkString}
  ): ${resultType} = reader.nextTag() match {
    case -1 =>
      ${factoryName}(${props.map(p=>s"adapter_${p.name}.decodeFix(prep_${p.name})").mkString(", ")})
    ${props.map(outerProp=>s"\n      case ${outerProp.id} => decodeMore(reader${
        props.map(p =>
          if(p eq outerProp)
            s", adapter_${p.name}.decodeReduce(reader,prep_${p.name})"
          else s", prep_${p.name}"
        ).mkString
      })").mkString}
    case _ =>
      val r = reader.peekFieldEncoding.rawProtoAdapter.decode(reader) //do we need to report r?
      decodeMore(reader${props.map(p => s", prep_${p.name}").mkString})
  }
  def decode(reader: ProtoReader): ${resultType} = {
    val token = reader.beginMessage();
    val res = decodeMore(reader${props.map(p=>s", adapter_${p.name}.defaultValue").mkString})
    reader.endMessage(token)
    res
  }
}
        """)
    )
  }

  def getProtocol(parseContext: ParseContext, objectName: String, stats: List[Stat], c4ann: String): Seq[Generated] = {
      //println(t.structure)
    val classes = Util.matchClass(stats)
    val protoGenerated: List[Generated] = stats.collect{
      case q"..$mods trait ${Type.Name(tp)} { ..$stats }" =>
        stats.foreach{
          case q"def $_: $_" => ()
          case t: Tree => Utils.parseError(t, parseContext)
        }
        List(
          GeneratedTraitDef(tp),
          GeneratedImport(s"""\nimport $objectName.$tp"""),
          GeneratedCode(
            s"\n$c4ann final class ${tp}ProtoAdapterProvider(inner: ProtoAdapter[Product]) {" +
            s"\n  @provide def getProtoAdapter: Seq[ProtoAdapter[$tp]] = List(inner.asInstanceOf[ProtoAdapter[$tp]])" +
            //s"\n  @provide def getHasId: Seq[HasId] = List(inner.asInstanceOf[HasId])" +
            s"\n}"
          )
        )
    }.flatten.toList ::: classes.flatMap(getAdapter(parseContext,objectName,_,c4ann))

    //  case q"..$mods case class ${Type.Name(messageName)} ( ..$params ) extends ..$ext" =>

    def filterAllowedTraits(traits: Set[String]): List[String] =
      traits.toList.filterNot(_ == "T_Time")

    val traitDefSeq = protoGenerated.collect{ case m: GeneratedTraitDef => m.name }
    val traitDefs = traitDefSeq.toSet
    val traitUses = protoGenerated.collect{ case m: GeneratedTraitUsage => m.name }.toSet
    val traitIllegal = filterAllowedTraits(traitUses -- traitDefs)
    if(traitIllegal.nonEmpty) throw new Exception(s"can not extend from non-local traits $traitIllegal")

    // todo: compat .components
    GeneratedImport("\nimport ee.cone.c4proto._") ::
    GeneratedImport("\nimport ee.cone.c4di._") ::
    protoGenerated.collect{ case c: GeneratedImport => c } :::
    protoGenerated.collect{ case c: GeneratedCode => c }
  }
}
