package ee.cone.c4generator

import scala.meta._

case class TagParam(paramName: String, paramTypeName: String, paramTypeExpr: String, defaultValue: Option[String], isList: Boolean, isReceiver: Boolean)

object TagGenerator extends Generator {
  def get(parseContext: ParseContext): List[Generated] = parseContext.stats.flatMap{
    case Defn.Trait(Seq(mod"@c4tags(...$e)"),Type.Name(traitName),tParams,y,code) =>
      val tParamNameOpt = tParams match {
        case Seq() => None
        case Seq(tparam"..$_ ${Type.Name(nm)} <: $_") => Option(nm)
      }
      val tParamStr = tParamNameOpt.fold("")(v=>s"[$v]")
      val mod = mod"@c4(...$e)".syntax
      val res: List[TagStatements] = code.stats.map{
        case defDef@q"..$mods def $defName(...$args): $outType" =>
          val clientType = mods match {
            case Seq() => None
            case Seq(mod"@c4tag(${Lit(t: String)})") => Option(t)
          }
          val argByIsChild = args.flatten.map{
            case p@Term.Param(Nil,Term.Name(paramName),Some(paramType),defVal) =>
              paramType match {
                case t"VDom[${Type.Name(paramTypeName)}]" =>
                  (true,TagParam(paramName, paramTypeName, paramTypeName, None, isList = false, isReceiver = false))
                case t"List[VDom[${Type.Name(paramTypeName)}]]" =>
                  (true,TagParam(paramName, paramTypeName, paramTypeName, None, isList = true, isReceiver = false))
                case Type.Name(paramTypeName) =>
                  (false,TagParam(paramName, paramTypeName, paramTypeName, defVal.map(_.toString), isList = false, isReceiver = false))
                case t"List[${Type.Name(paramTypeName)}]" =>
                  (false,TagParam(paramName, paramTypeName, paramTypeName, defVal.map(_.toString), isList = true, isReceiver = false))
                case Type.Apply(Type.Name(paramTypeNameOuter), List(Type.Name(paramTypeNameInner))) =>
                  val paramTypeExpr = s"$paramTypeNameOuter[$paramTypeNameInner]"
                  val paramTypeName = s"${paramTypeNameOuter}Of$paramTypeNameInner"
                  val isReceiver = tParamNameOpt.contains(paramTypeNameInner)
                  (false,TagParam(paramName, paramTypeName, paramTypeExpr, defVal.map(_.toString), isList = false, isReceiver))
                case p =>
                  throw new Exception(s"unsupported tag param type [$p] ${p.structure} of $defName")
              }
          }.groupMap(_._1)(_._2)
          val (outIsChild,outTypeName) = outType match {
            case t"VDom[${Type.Name(name)}]" => (true,name)
            case Type.Name(name) => (false,name)
          }
          val childArgs = argByIsChild.getOrElse(true,Nil).toList
          if(!outIsChild && childArgs.nonEmpty)
            throw new Exception(s"VDom in needs VDom out for $defName")
          val attrArgs = argByIsChild.getOrElse(false,Nil).toList match {
            case p if !outIsChild => p
            case TagParam("key","String",_,_,false,_) :: attrArgs => attrArgs
            case p => throw new Exception(s"need key param for $defName")
          }
          val tagTypeName = Util.pkgNameToId(s"$traitName.$defName")
          val localParamNameOpt = tParamNameOpt.filter(_=>attrArgs.exists(_.isReceiver))
          TagStatements(defDef.syntax, defName.value, attrArgs, childArgs, outIsChild, outTypeName, mod, tagTypeName, clientType, traitName, localParamNameOpt)
      }
      res.map(_.getTagClass).map(GeneratedCode) ++
      tParamNameOpt.fold(List.empty[String])(v=>List(
        s"\ntrait General$traitName",
        s"\n$mod final class ${traitName}Provider(tags: $traitName[Nothing]){ " +
        s"\n  def get[T]: $traitName[T] = tags.asInstanceOf[$traitName[T]] " +
        s"\n}"
      )).map(GeneratedCode) ++
      List(GeneratedCode(
        s"\n$mod final class ${traitName}Impl(" +
        "\n  child: VDomFactory, " +
        res.flatMap(_.getArg).distinct.mkString +
        s"\n) extends ${tParamNameOpt.fold(traitName)(v=>s"$traitName[Nothing]")} {" +
        tParamNameOpt.fold("")(v=>s"\n  type $v = Nothing") +
        res.map(_.getDef).mkString +
        s"\n}"
      ))
    case _ => Nil
  } ::: parseContext.stats.collect{ case Defn.Trait(Seq(mod"@c4tagSwitch(...$e)"),Type.Name(traitName),x,y,code) =>
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
  }.groupMap(_._1)(_._2).map{ case (cl,defs) =>
    defs.map(s=>s"\n  $s").mkString(s"\n$cl{","","\n}")
  }.toList.sorted.map(GeneratedCode)
}

case class TagStatements(
  defDef: String, defName: String,
  attrArgs: List[TagParam], childArgs: List[TagParam],
  outIsChild: Boolean, outTypeName: String,
  mod: String, tagTypeName: String, clientType: Option[String],
  traitName: String, tParamNameOpt: Option[String],
){
  def getArg: List[String] =
    attrArgs.filterNot(_.isReceiver).map(param =>
      s"\n  a${param.paramTypeName}JsonValueAdapter: JsonValueAdapter[${param.paramTypeExpr}], "
    )
  def getCreate: String = {
    val attrArgsStr = attrArgs.map(_.paramName).mkString(",")
    s"${tagTypeName}${if(tParamNameOpt.isEmpty) "" else "[Nothing]"}($attrArgsStr)(this)"
  }
  def getDef: String = indentStr(
    if(outIsChild) {
      val childArgsStr = childArgs.foldRight("Nil")((param,res)=>
        s"child.addGroup(key,${quot(param.paramName)},${param.paramName},$res)"
      )
      s"$defDef = " :: indent(
        s"child.create[$outTypeName](key," :: indent(List(s"$getCreate,",childArgsStr)) ::: ")" :: Nil
      ) ::: getAdapter(s"builder.append(${quot("identity")}).append(${quot("ctx")})" ::Nil)
    }
    else if(attrArgs.nonEmpty) s"$defDef = $getCreate" :: getAdapter(Nil)
    else s"$defDef = $tagTypeName" :: Nil
  )
  def quot(v: String): String = '"'+v+'"'
  def paramTypeFullExpr(param: TagParam) =
    if(param.isList) s"List[${param.paramTypeExpr}]" else param.paramTypeExpr
  def getTagClassInner(tParams: String, extendsStr: String, body: List[String]): String =
    s"\nfinal case class $tagTypeName$tParams(" +
    indentStr(attrArgs.map{param =>
      s"${param.paramName}: ${paramTypeFullExpr(param)}, "
    }) +
    s"\n)(val factory: ${traitName}Impl) extends $extendsStr {" +
    indentStr(
      s"def appendJson(builder: MutableJsonBuilder): Unit = factory.${defName}Append(this, builder)" ::
      body
    ) +
    "\n}"
  def getTagClass: String =
    if(outIsChild)
      tParamNameOpt.fold(getTagClassInner("","VDomValue", Nil))(tParamName=>
        getTagClassInner(
          s"[$tParamName]",
          "ResolvingVDomValue",
          s"def resolve(name: String): Option[Resolvable] = (name match { " ::
            indent(
              attrArgs.filter(_.isReceiver).map(param=>s"case ${quot(param.paramName)} => Option(${param.paramName})") :::
                "case _ => None" :: Nil
            ) :::
            "}).collect{ case p: Resolvable => p }" :: Nil
        )
      )
    else if(attrArgs.nonEmpty) getTagClassInner("",outTypeName, Nil)
    else
      s"\ncase object ${tagTypeName} extends $outTypeName {" +
      s"\n  def appendJson(builder: MutableJsonBuilder): Unit = " +
      s"\n    builder.just.append(${quot(clientType.get)})" +
      s"\n}"

  def getAdapter(addBody: List[String]): List[String] =
    s"def ${defName}Append(value: $tagTypeName${if(tParamNameOpt.isEmpty) "" else "[_]"}, builder: MutableJsonBuilder): Unit = {" ::
    indent(
      "builder.startObject()" ::
      addBody :::
      clientType.map(tp => s"builder.append(${quot("tp")}).append(${quot(tp)})").toList :::
      attrArgs.filterNot(_.isReceiver).flatMap(getAdapterBodyArg) :::
      "builder.end()" :: Nil
    ) :::
    s"}" :: Nil

  def getAdapterBodyArg(param: TagParam): List[String] = {
    val value = s"value.${param.paramName}"
    val appendOne = s"a${param.paramTypeName}JsonValueAdapter.appendJson"
    val appendValue = if(param.isList) List(
      s"builder.startArray()",
      s"$value.foreach(v=>$appendOne(v,builder))",
      s"builder.end()"
    ) else List(s"$appendOne($value, builder)")
    val appendKeyValue = s"builder.just.append(${quot(param.paramName)})" :: appendValue
    if(param.defaultValue.isEmpty) appendKeyValue
    else s"if($value!=${param.defaultValue.get}){" :: indent(appendKeyValue) ::: "}" :: Nil
  }
  def indent(l: List[String]): List[String] = l.map(v=>s"  $v")
  def indentStr(l: List[String]): String = indent(l).map(v=>s"\n$v").mkString
}



/*
pass notDefault
name vs obj
single

2282 1672
 */