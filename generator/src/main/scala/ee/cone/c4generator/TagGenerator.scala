package ee.cone.c4generator

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.meta._

trait ToJsonOptions

object ToJsonOptions {
  def apply(
    paramTypeName: String, paramTypeExpr: String,
    defaultValue: Option[String],
    isList: Boolean,
    isOption: Boolean
  ): ToJsonOptions =
    ToJsonOptionsDefault(paramTypeName, paramTypeExpr, defaultValue, isList, isOption)
}

case class ToJsonOptionsDefault(
  paramTypeName: String, paramTypeExpr: String,
  defaultValue: Option[String],
  isList: Boolean,
  isOption: Boolean,
) extends ToJsonOptions

case class ReceiverToJsonOptions(defaultValue: Option[String]) extends ToJsonOptions

case class TagParam(
  paramName: String,
  paramTypeFullExpr: String,
  toJsonOptions: Option[ToJsonOptions],
  isReceiver: Boolean,
  toElement: Option[String],
)

object TagGenerator extends Generator {

  val defaultImports: List[GeneratedImport] = List(
    GeneratedImport("import ee.cone.c4di._"),
    GeneratedImport("import ee.cone.c4vdom.Types._"),
    GeneratedImport("import ee.cone.c4vdom._"),
  )

  def get(parseContext: ParseContext): List[Generated] = parseContext.stats.flatMap {
    case Defn.Trait(Seq(mod"@c4tags(...$e)"), Type.Name(traitName), tParams, y, code) =>
      val tParamNameOpt = tParams match {
        case Seq() => None
        case Seq(tparam"..$_ ${Type.Name(nm)} <: $_") => Option(nm)
      }
      val mod = mod"@c4(...$e)".syntax
      val res: List[TagStatements] = code.stats.map {
        case defDef@q"..$mods def $defName(...$args): ${Type.Name(outTypeName)}" =>
          val (clientType, outIsChild, needsPath) = mods match {
            case Seq(mod"@c4val") => (None, false, false)
            case Seq(mod"@c4val(${Lit(t: String)})") => (Option(t), false, false)
            case Seq(mod"@c4el(${Lit(t: String)})") => (Option(t), true, false)
            case Seq(mod"@c4elPath(${Lit(t: String)})") => (Option(t), true, true)
          }

          val params = args.flatten.map {
            case p@Term.Param(Nil, Term.Name(paramName), Some(paramType), defVal) =>
              val defValStr = defVal.map(_.toString)
              val paramTypeFullExpr = paramType.toString
              paramType match {
                case t"ViewRes" =>
                  TagParam(paramName, paramTypeFullExpr, None, isReceiver = false, Option(paramName))
                case t"ElList[${Type.Name(_)}]" =>
                  TagParam(paramName, paramTypeFullExpr, None, isReceiver = false, Option(s"$paramName.map(_.toChildPair)"))
                case Type.Name(paramTypeName) =>
                  TagParam(paramName, paramTypeFullExpr, Option(ToJsonOptions(paramTypeName, paramTypeName, defValStr, isList = false, isOption = false)), isReceiver = false, None)
                case t"List[${Type.Name(paramTypeName)}]" =>
                  TagParam(paramName, paramTypeFullExpr, Option(ToJsonOptions(paramTypeName, paramTypeName, defValStr, isList = true, isOption = false)), isReceiver = false, None)
                case t"Option[${Type.Name(paramTypeName)}]" =>
                  TagParam(paramName, paramTypeFullExpr, Option(ToJsonOptions(paramTypeName, paramTypeName, defValStr, isList = false, isOption = true)), isReceiver = false, None)
                case Type.Apply(Type.Name(_), List(Type.Name(paramTypeNameInner))) if tParamNameOpt.contains(paramTypeNameInner) =>
                  TagParam(paramName, paramTypeFullExpr, Option(ReceiverToJsonOptions(defValStr)), isReceiver = true, None)
                case p =>
                  throw new Exception(s"unsupported tag param type [$p] ${p.structure} of $defName")
              }
          }
          if (!outIsChild && params.exists(_.toElement.nonEmpty))
            throw new Exception(s"$defName takes elements so it should return element")
          val tagTypeName = Util.pkgNameToId(s"$traitName.$defName")
          val localParamNameOpt = tParamNameOpt.filter(_ => params.exists(_.isReceiver))
          TagStatements(defDef.syntax, defName.value, params.toList, outIsChild, outTypeName, mod, tagTypeName, clientType, traitName, localParamNameOpt, needsPath)
      }
      res.map(_.getTagClass).map(GeneratedCode) ++
        tParamNameOpt.fold(List.empty[String])(v => List(
          s"\ntrait General$traitName",
          JoinStr(
            s"\n$mod final class ${traitName}Provider(tags: $traitName[Nothing]){ ",
            s"\n  def get[T]: $traitName[T] = tags.asInstanceOf[$traitName[T]] ",
            s"\n}"
          )
        )).map(GeneratedCode) ++
        List(GeneratedCode(JoinStr(
          s"\n$mod final class ${traitName}Impl(",
          "\n  val child: VDomFactory, ",
          res.flatMap(_.getArg).distinct.mkString,
          s"\n) extends ${tParamNameOpt.fold(traitName)(v => s"$traitName[Nothing]")} {",
          tParamNameOpt.fold("")(v => s"\n  type $v = Nothing"),
          res.map(_.getDef).mkString,
          s"\n}"
        )))
    case _ => Nil
  } ::: parseContext.stats.collect { case Defn.Trait(Seq(mod"@c4tagSwitch(...$e)"), Type.Name(traitName), x, y, code) =>
    val mod = mod"@c4(...$e)".syntax
    val id = Util.pathToId(parseContext.path)
    val pf = e.flatten match {
      case Seq() => ""
      case Seq(Lit(n: String)) => n
    }
    (
      s"$mod final class ${id}${pf}JsonValueAdapterProviders(adapters: List[JsonValueAdapter[ToJson]])",
      s"@provide def for$traitName: Seq[JsonValueAdapter[$traitName]] = adapters"
    )
  }.groupMap(_._1)(_._2).map { case (cl, defs) =>
    defs.map(s => s"\n  $s").mkString(s"\n$cl{", "", "\n}")
  }.toList.sorted.map(GeneratedCode) match {
    case Nil => Nil
    case code => defaultImports ::: code
  }
}

case class TagStatements(
  defDef: String, defName: String, args: List[TagParam],
  outIsChild: Boolean, outTypeName: String,
  mod: String, tagTypeName: String, clientType: Option[String],
  traitName: String, tParamNameOpt: Option[String],
  needsPath: Boolean,
) {
  def getArg: List[String] = for {
    param <- args
    opt <- param.toJsonOptions.collect { case d: ToJsonOptionsDefault => d }
  } yield s"\n  a${opt.paramTypeName}JsonValueAdapter: JsonValueAdapter[${opt.paramTypeExpr}], "


  def getCreate: String = {
    val attrArgsStr = args.map(_.paramName).mkString(",")
    s"${tagTypeName}${if (tParamNameOpt.isEmpty) "" else "[Nothing]"}($attrArgsStr)(this)"
  }
  def getDef: String = indentStr(
    if (args.nonEmpty) s"$defDef = $getCreate" :: getAdapter(
      if (outIsChild) s"builder.append(${quot("identity")}).append(${quot("ctx")})" :: Nil else Nil
    )
    else s"$defDef = $tagTypeName" :: Nil
  )
  def quot(v: String): String = '"' + v + '"'
  def getTagClassInner(tParams: String, extendsStr: String, body: List[String]): String = JoinStr(
    s"\nfinal case class $tagTypeName$tParams(",
    indentStr(args.map { param =>
      s"${param.paramName}: ${param.paramTypeFullExpr}, "
    }),
    s"\n)(val factory: ${traitName}Impl) extends $outTypeName$extendsStr {",
    indentStr(
      s"def appendJson(builder: MutableJsonBuilder): Unit = factory.${defName}Append(this, builder)" :: body
    ),
    "\n}"
  )
  def getTagClass: String =
    if (outIsChild) {
      val elementArgs = args.filter(_.toElement.nonEmpty)
      val toChildPairStr: List[String] = if (elementArgs.isEmpty)
        s"def toChildPair[T]: ChildPair[T] = factory.child.create(key,this,Nil)" :: Nil
      else {
        val childArgsStr = elementArgs.foldRight("Nil")((param, res) =>
          s"factory.child.addGroup(_key,${quot(param.paramName)},${param.toElement.get},$res)"
        )
        Nil :::
          s"def toChildPair[T]: ChildPair[T] = {" ::
          indent(List(
            s"val _key = key",
            s"val _copy = copy(${elementArgs.map(param => s"${param.paramName}=Nil").mkString(",")})(factory)",
            s"factory.child.create(_key,_copy,$childArgsStr)"
          )) :::
          "}" :: Nil
      }
      tParamNameOpt.fold(getTagClassInner("", " with VDomValue", toChildPairStr))(tParamName =>
        getTagClassInner(
          s"[$tParamName]",
          " with ResolvingVDomValue",
          Nil :::
            s"def resolve(name: String): Option[Resolvable] = (name match { " ::
            indent(
              args.filter(_.isReceiver).map(param => s"case ${quot(param.paramName)} => Option(${param.paramName})") :::
                "case _ => None" :: Nil
            ) :::
            "}).collect{ case p: Resolvable => p }" ::
            toChildPairStr
        )
      )
    }
    else if (args.nonEmpty) getTagClassInner("", "", Nil)
    else JoinStr(
      s"\ncase object ${tagTypeName} extends $outTypeName {",
      s"\n  def appendJson(builder: MutableJsonBuilder): Unit = ",
      s"\n    builder.just.append(${quot(clientType.get)})",
      s"\n}"
    )
  def getAdapter(addBody: List[String]): List[String] = Nil :::
    s"def ${defName}Append(value: $tagTypeName${if (tParamNameOpt.isEmpty) "" else "[_]"}, builder: MutableJsonBuilder): Unit = {" ::
    indent(Nil :::
      "builder.startObject()" ::
      Option.when(needsPath)("builder.append(\"path\").append(\"I\")").toList :::
      addBody :::
      clientType.map(tp => s"builder.append(${quot("tp")}).append(${quot(tp)})").toList :::
      (for {
        param <- args
        opt <- param.toJsonOptions.toList
        line <- getAdapterBodyArg(param, opt)
      } yield line) :::
      "builder.end()" :: Nil
    ) :::
    s"}" :: Nil

  def optionCondition(isOption: Boolean, valueName: String): List[String] =
    if (isOption) s"$valueName.nonEmpty" :: Nil
    else Nil

  def andConditions(conditions: List[String]): String =
    conditions.filter(_.nonEmpty).mkString(" && ")

  def getAdapterBodyArg(param: TagParam, opt: ToJsonOptions): List[String] =
    opt match {
      case ToJsonOptionsDefault(paramTypeName, paramTypeExpr, defaultValue, isList, isOption) =>
        val value = s"value.${param.paramName}"
        val appendOne = s"a${paramTypeName}JsonValueAdapter.appendJson"
        val appendValue = if (isList) List(
          s"builder.startArray()",
          s"$value.foreach(v=>$appendOne(v,builder))",
          s"builder.end()"
        ) else if (isOption) List(
          s"$value.foreach(v=>$appendOne(v,builder))"
        ) else List(s"$appendOne($value, builder)")
        val appendKeyValue = s"builder.just.append(${quot(param.paramName)})" :: appendValue
        val defaultConditions =
          (if (defaultValue.nonEmpty) s"$value!=${defaultValue.get}" :: Nil else Nil) :::
            optionCondition(isOption, value)
        defaultConditions match {
          case Nil => appendKeyValue
          case ne => s"if(${andConditions(ne)}){" :: indent(appendKeyValue) ::: "}" :: Nil
        }
      case ReceiverToJsonOptions(defaultValue) =>
        defaultValue match {
          case Some(defValue) =>
            val value = s"value.${param.paramName}"
            s"builder.append(${quot(param.paramName)}).append($value!=$defValue)" :: Nil
          case None => s"builder.append(${quot(param.paramName)}).append(true)" :: Nil
        }
      case _ => ???
    }
  def indent(l: List[String]): List[String] = l.map(v => s"  $v")
  def indentStr(l: List[String]): String = indent(l).map(v => s"\n$v").mkString
}


/*
pass notDefault
single

2282 1672
 */

// Generates c4gen.<Name>.ts alongside each Scala sapi file that contains @c4tags traits.
// Only handles new-API components (@c4el / @c4elPath) — legacy components have no @c4tags.
// TODO: add caching (currently re-parses all scala files on every sbt c4build)
class TsTagWillGenerator extends WillGenerator {

  private sealed trait SwitchVariant
  private case class StringVariant(literal: String) extends SwitchVariant
  private case class ObjectVariant(params: List[Term.Param], tParamNameOpt: Option[String], tp: Option[String] = None) extends SwitchVariant
  private case class ElVariant(propsName: String) extends SwitchVariant

  def get(ctx: WillGeneratorContext): List[(Path, Array[Byte])] =
    ctx.fromFiles
      .filter(_.getFileName.toString.endsWith(".scala"))
      .flatMap { path =>
        val content = new String(Files.readAllBytes(path), UTF_8)
        if (!content.contains("@c4tags")) Nil
        else DefaultWillGenerator.getParseContext(path, content).toList.flatMap { parseCtx =>
          val tsContent = generateFileContent(parseCtx)
          if (tsContent.isEmpty) Nil
          else {
            val base = path.getFileName.toString.stripSuffix(".scala")
            List(path.resolveSibling(s"c4gen.$base.ts") -> tsContent.getBytes(UTF_8))
          }
        }
      }

  private def tParamName(tParams: Seq[Type.Param]): Option[String] = tParams match {
    case Seq() => None
    case Seq(tparam"..$_ ${Type.Name(nm)} <: $_") => Option(nm)
    case _ => None
  }

  private def generateFileContent(parseCtx: ParseContext): String = {
    // Phase 1: collect @c4tagSwitch trait names and their @c4val implementations
    val switchTraitNames: Set[String] = parseCtx.stats.collect {
      case Defn.Trait(Seq(mod"@c4tagSwitch(...$_)"), Type.Name(name), _, _, _) => name
    }.toSet
    val commonImports = scala.collection.mutable.Set.empty[String]

    val variantsByTrait: Map[String, List[SwitchVariant]] = parseCtx.stats.flatMap {
      case Defn.Trait(Seq(mod"@c4tagSwitch(...$_)"), Type.Name(childName), _, _, template) =>
        template.inits.collect {
          case Init(Type.Name(parentName), _, _) if switchTraitNames(parentName) =>
            (parentName, ElVariant(childName))
        }
      case Defn.Trait(Seq(mod"@c4tags(...$_)"), _, tParams, _, template) =>
        val tpOpt = tParamName(tParams)
        template.stats.flatMap {
          case q"..$mods def $_(...$args): ${Type.Name(retType)}" if switchTraitNames(retType) =>
            val flatArgs = args.flatten.toList
            mods match {
              case Seq(mod"@c4val(${Lit(t: String)})") if flatArgs.isEmpty => List((retType, StringVariant(t)))
              case Seq(mod"@c4val(...$e)") if flatArgs.nonEmpty            =>
                val tp = e.flatten.collectFirst { case Lit(t: String) => t }
                List((retType, ObjectVariant(flatArgs, tpOpt, tp)))
              case Seq(mod"@c4el(${Lit(t: String)})")                      => List((retType, ElVariant(s"${t}Props")))
              case _                                                        => Nil
            }
          case _ => Nil
        }
      case _ => Nil
    }.groupBy(_._1).transform((_, vs) => vs.map(_._2))

    // Phase 2: generate interfaces
    val interfaces = parseCtx.stats.flatMap {
      case Defn.Trait(Seq(mod"@c4tags(...$_)"), _, tParams, _, template) =>
        val tpOpt = tParamName(tParams)
        template.stats.flatMap {
          case q"..$mods def $_(...$args): $_" =>
            mods match {
              case Seq(mod"@c4el(${Lit(t: String)})")     => List(toInterface(t, tpOpt, args.flatten.toList, hasPath = false, switchTraitNames, commonImports))
              case Seq(mod"@c4elPath(${Lit(t: String)})") => List(toInterface(t, tpOpt, args.flatten.toList, hasPath = true,  switchTraitNames, commonImports))
              case _                                       => Nil
            }
          case _ => Nil
        }
      case _ => Nil
    }

    if (interfaces.isEmpty) return ""

    // Phase 3: assemble output
    val typeAliases = switchTraitNames.toList.sorted
      .map(n => toTypeAlias(n, variantsByTrait.getOrElse(n, Nil), switchTraitNames, commonImports))

    val reactImport  = if (interfaces.exists(_.contains("ReactElement"))) "import type { ReactElement } from 'react'\n" else ""
    val commonImport = if (commonImports.nonEmpty)
      s"import type { ${commonImports.toList.sorted.mkString(", ")} } from 'c4f/sapi/ee/cone/c4ui/c4gen.CommonElementsApi'\n"
    else ""
    val importsBlock = List(reactImport, commonImport).filter(_.nonEmpty).mkString + "\n"
    val aliasSection = if (typeAliases.isEmpty) "" else typeAliases.mkString("\n") + "\n\n"

    s"// THIS FILE IS GENERATED\n\n$importsBlock$aliasSection${interfaces.mkString("\n\n")}\n"
  }

  private def toTypeAlias(
    traitName: String,
    variants: List[SwitchVariant],
    switchTraitNames: Set[String],
    commonImports: scala.collection.mutable.Set[String],
  ): String = {
    if (variants.isEmpty)
      return s"export type $traitName = unknown // TODO: no @c4val found in this file"
    val parts = variants.map {
      case StringVariant(lit) => s""""$lit""""
      case ElVariant(propsName) => propsName
      case ObjectVariant(params, tpOpt, tp) =>
        val tpField = tp.map(t => s"""tp: "$t"""").toList
        val fields = tpField ::: params.collect {
          case Term.Param(_, Term.Name(n), Some(t), defVal) =>
            val opt = if (defVal.nonEmpty) "?" else ""
            s"$n$opt: ${toTsType(t, tpOpt, switchTraitNames, commonImports)}"
        }
        s"{ ${fields.mkString(", ")} }"
    }
    s"export type $traitName = ${parts.mkString(" | ")}"
  }

  private def toInterface(
    name: String,
    tpOpt: Option[String],
    params: List[Term.Param],
    hasPath: Boolean,
    switchTraitNames: Set[String],
    commonImports: scala.collection.mutable.Set[String],
  ): String = {
    val fields =
      "  identity: object" ::
      (if (hasPath) List("  path: string") else Nil) :::
      params.collect {
        case Term.Param(_, Term.Name(paramName), Some(paramType), defVal) if paramName != "key" =>
          val opt = if (isOptional(paramType, hasDefault = defVal.nonEmpty)) "?" else ""
          s"  $paramName$opt: ${toTsType(paramType, tpOpt, switchTraitNames, commonImports)}"
      }
    s"export interface ${name}Props {\n${fields.mkString("\n")}\n}"
  }

  private def isOptional(paramType: Type, hasDefault: Boolean): Boolean = paramType match {
    case Type.Name("ViewRes")               => true
    case Type.Apply(Type.Name("ElList"), _) => true
    case Type.Apply(Type.Name("Option"), _) => true
    case _                                  => hasDefault
  }

  private def toTsType(
    paramType: Type,
    tpOpt: Option[String],
    switchTraitNames: Set[String],
    commonImports: scala.collection.mutable.Set[String],
  ): String = paramType match {
    case Type.Name("ViewRes")                                                      => "ReactElement[]"
    case Type.Apply(Type.Name("ElList"), _)                                        => "ReactElement[]"
    case Type.Apply(Type.Name(_), List(Type.Name(inner))) if tpOpt.contains(inner) => "boolean" // Receiver[C]
    case Type.Apply(Type.Name("List"), List(Type.Name(n)))                         => s"${resolveScalar(n, switchTraitNames, commonImports)}[]"
    case Type.Apply(Type.Name("Option"), List(Type.Name(n)))                       => resolveScalar(n, switchTraitNames, commonImports)
    case Type.Name(n)                                                              => resolveScalar(n, switchTraitNames, commonImports)
    case other                                                                     => s"unknown /* TODO: $other */"
  }

  private def resolveScalar(
    name: String,
    switchTraitNames: Set[String],
    commonImports: scala.collection.mutable.Set[String],
  ): String = name match {
    case "String"                  => "string"
    case "Boolean"                 => "boolean"
    case "Int" | "Double" | "Float" => "number"
    case "Long"                    => "string" // Long can't round-trip through JS number; sent as string
    case "Em" | "BigDecimal"       => "number"
    case "CSSClassName"            => "string"
    case n if switchTraitNames(n)  => n
    case n => commonImports += n; n
  }
}