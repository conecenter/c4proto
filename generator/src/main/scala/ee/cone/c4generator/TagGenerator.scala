package ee.cone.c4generator

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

  val defaultImports: List[GeneratedImport] =
    GeneratedImport("import ee.cone.c4di._") ::
      GeneratedImport("import ee.cone.c4vdom.Types._") ::
      GeneratedImport("import ee.cone.c4vdom._") ::
      Nil

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
          s"\n$mod final class ${traitName}Provider(tags: $traitName[Nothing]){ " +
            s"\n  def get[T]: $traitName[T] = tags.asInstanceOf[$traitName[T]] " +
            s"\n}"
        )).map(GeneratedCode) ++
        List(GeneratedCode(
          s"\n$mod final class ${traitName}Impl(" +
            "\n  val child: VDomFactory, " +
            res.flatMap(_.getArg).distinct.mkString +
            s"\n) extends ${tParamNameOpt.fold(traitName)(v => s"$traitName[Nothing]")} {" +
            tParamNameOpt.fold("")(v => s"\n  type $v = Nothing") +
            res.map(_.getDef).mkString +
            s"\n}"
        ))
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
  def getTagClassInner(tParams: String, extendsStr: String, body: List[String]): String =
    s"\nfinal case class $tagTypeName$tParams(" +
      indentStr(args.map { param =>
        s"${param.paramName}: ${param.paramTypeFullExpr}, "
      }) +
      s"\n)(val factory: ${traitName}Impl) extends $outTypeName$extendsStr {" +
      indentStr(
        s"def appendJson(builder: MutableJsonBuilder): Unit = factory.${defName}Append(this, builder)" ::
          body
      ) +
      "\n}"
  def getTagClass: String =
    if (outIsChild) {
      val elementArgs = args.filter(_.toElement.nonEmpty)
      val toChildPairStr: List[String] = if (elementArgs.isEmpty)
        s"def toChildPair[T]: ChildPair[T] = factory.child.create(key,this,Nil)" :: Nil
      else {
        val childArgsStr = elementArgs.foldRight("Nil")((param, res) =>
          s"factory.child.addGroup(_key,${quot(param.paramName)},${param.toElement.get},$res)"
        )
        s"def toChildPair[T]: ChildPair[T] = {" ::
          indent(List(
            s"val _key = key",
            s"val _copy = copy(${elementArgs.map(param => s"${param.paramName}=Nil").mkString(",")})(factory)",
            s"factory.child.create(_key,_copy,$childArgsStr)"
          )) ::: "}" :: Nil
      }
      tParamNameOpt.fold(getTagClassInner("", " with VDomValue", toChildPairStr))(tParamName =>
        getTagClassInner(
          s"[$tParamName]",
          " with ResolvingVDomValue",
          s"def resolve(name: String): Option[Resolvable] = (name match { " ::
            indent(
              args.filter(_.isReceiver).map(param => s"case ${quot(param.paramName)} => Option(${param.paramName})") :::
                "case _ => None" :: Nil
            ) :::
            "}).collect{ case p: Resolvable => p }" :: toChildPairStr
        )
      )
    }
    else if (args.nonEmpty) getTagClassInner("", "", Nil)
    else
      s"\ncase object ${tagTypeName} extends $outTypeName {" +
        s"\n  def appendJson(builder: MutableJsonBuilder): Unit = " +
        s"\n    builder.just.append(${quot(clientType.get)})" +
        s"\n}"

  def getAdapter(addBody: List[String]): List[String] =
    s"def ${defName}Append(value: $tagTypeName${if (tParamNameOpt.isEmpty) "" else "[_]"}, builder: MutableJsonBuilder): Unit = {" ::
      indent(
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