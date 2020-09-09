package ee.cone.c4generator

import scala.meta._

case class TagParam(paramName: String, paramTypeName: String, defaultValue: Option[String])

object TagGenerator extends Generator {
  def get(parseContext: ParseContext): List[Generated] = parseContext.stats.flatMap{
    case Defn.Trait(Seq(mod"@c4tags(...$e)"),Type.Name(traitName),x,y,code) =>
      val mod = mod"@c4(...$e)".syntax
      val modMulti = mod"@c4multi(...$e)".syntax
      val res: List[TagStatements] = code.stats.map{
        case defDef@q"..$mods def $defName(...$args): VDom[${Type.Name(outTypeName)}]" =>
          val clientType = mods match {
            case Seq() => None
            case Seq(mod"@c4tag(${Lit(t: String)})") => Option(t)
          }
          val argByIsChild = args.flatten.map{
            case p@Term.Param(Nil,Term.Name(paramName),Some(paramType),defVal) =>
              paramType match {
                case t"VDom[${Type.Name(paramTypeName)}]" =>
                  (true,TagParam(paramName, paramTypeName, None))
                case t"VDom[${Type.Name(paramTypeName)}]*" =>
                  (true,TagParam(paramName, paramTypeName, None))
                case Type.Name(paramTypeName) =>
                  (false,TagParam(paramName, paramTypeName, defVal.map(_.toString)))
                case p =>
                  throw new Exception(s"unsupported tag param type [$p] ${p.structure} of $defName")
              }
          }.groupMap(_._1)(_._2)
          val childArgs = argByIsChild.getOrElse(true,Nil).toList
          val attrArgs = argByIsChild.getOrElse(false,Nil).toList match {
            case TagParam("key","String",_) :: attrArgs => attrArgs
            case p => throw new Exception(s"need key param for $defName")
          }
          val tagTypeName = Util.pkgNameToId(s"$traitName.$defName")
          TagStatements(defDef.syntax, defName.value, attrArgs, childArgs, outTypeName, mod, modMulti, tagTypeName, clientType)
      }
      val tagClasses = res.map(_.getTagClass)
      MultiGenerator.getForStats(tagClasses.map(_.parse[Stat].get)) ++
      tagClasses.map(GeneratedCode) ++
      res.map(_.getAdapterClass).map(GeneratedCode) ++
      List(GeneratedCode(
        s"\n$mod final class ${traitName}Impl(" +
        "\n  child: VDomFactory, " +
        res.map(_.getArg).mkString +
        s"\n) extends ${traitName} {" +
        res.map(_.getDef).mkString +
        s"\n}"
      ))
    case _ => Nil
  }
}

case class TagStatements(
  defDef: String, defName: String,
  attrArgs: List[TagParam], childArgs: List[TagParam], outTypeName: String,
  mod: String, modMulti: String, tagTypeName: String, clientType: Option[String],
){
  def tpAttrArgs: List[TagParam] =
    clientType.map(_=>TagParam("tp","ClientComponentType",None)).toList ::: attrArgs
  def getArg: String = s"\n  ${defName}Factory:  ${tagTypeName}Factory, "
  def getDef: String = {
    val childArgsStr = childArgs.foldRight("Nil")((param,res)=>
      s"child.addGroup(key,${quot(param.paramName)},${param.paramName},$res)"
    )
    val attrArgsStr = attrArgs.map(_.paramName).mkString(",")
    s"\n  $defDef = child.create[$outTypeName](key,\n      ${defName}Factory.create($attrArgsStr),\n      $childArgsStr\n  )"
  }
  def quot(v: String): String = '"'+v+'"'
  def getTagClass: String =
    s"\n$modMulti final case class ${tagTypeName}(" +
    attrArgs.map(param=>s"\n  ${param.paramName}: ${param.paramTypeName}, ").mkString +
    s"\n)(adapter: JsonValueAdapter[${tagTypeName}]) extends ResolvingVDomValue {" +
    s"\n  def appendJson(builder: MutableJsonBuilder): Unit = adapter.appendJson(this, builder)" +
    clientType.map(tp => s"\n  def tp = ${quot(tp)} ").mkString +
    s"\n  def resolve(name: String): Option[Resolvable] = (name match { " +
    attrArgs.filter(param => param.paramTypeName!="Int" && param.paramTypeName!="Boolean")
      .map(param=>s"\n    case ${quot(param.paramName)} => Option(${param.paramName})").mkString +
    "\n    case _ => None" +
    "\n  }).collect{ case p: Resolvable => p }" +
    "\n}"
  def getAdapterClass: String =
    s"\n$mod final class ${tagTypeName}JsonValueAdapter(" +
    tpAttrArgs.map(param=>s"\n  ${param.paramName}JsonPairAdapter: JsonPairAdapter[${param.paramTypeName}], ").mkString +
    s"\n) extends JsonValueAdapter[${tagTypeName}] {" +
    s"\n  def appendJson(value: ${tagTypeName}, builder: MutableJsonBuilder): Unit = {" +
    s"\n    builder.startObject()" +
    s"\n    builder.append(${quot("identity")}).append(${quot("ctx")})" +
    tpAttrArgs.map{ param =>
      val cond = param.defaultValue.fold("")(v=>s"if(value.${param.paramName}!=$v) ")
      val append = s"${param.paramName}JsonPairAdapter.appendJson(${quot(param.paramName)}, value.${param.paramName}, builder)"
      s"\n    $cond$append"
    }.mkString +
    s"\n    builder.end()" +
    s"\n  }" +
    s"\n}"
}

/*




@c4 final class BbbJsonAdapter(
  a1JsonAdapter: JsonAdapter[A1],
  a2JsonAdapter: JsonAdapter[A2],
) extends JsonAdapter[Bbb] {
  def appendJson(key: String, value: Bbb, builder: MutableJsonBuilder): Unit = {
    builder.append(key)
    appendJson(value,builder)
  }
  def appendJson(value: Bbb, builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append("Bbb")
    a1JsonAdapter.appendJson("a1", value.a1, builder)
    a2JsonAdapter.appendJson("a2", value.a2, builder)
    builder.end()
  }
}

@c4multi final case class Bbb(a1: A1, a2: A2)(adapter: JsonAdapter[Bbb]) extends ToJson {
  def appendJson(builder: MutableJsonBuilder): Unit = adapter.appendJson(this, builder)
}

@c4 final class Ddd(
  child: ChildPairFactory,
  bbbFactory: BbbFactory,
) {
  def bbb(key: String, a1: A1, a2: A2)(c1: VDom[C1]*)(c2: VDom[C2]*): VDom[B] =
    child[B](key, bbbFactory.create(a1,a2), child.addGroup("c1",c1,child.addGroup("c2",c2,Nil)))


Tag
def
dep-s


def apply[C](key: VDomKey, theElement: VDomValue, elements: ViewRes): ChildPair[C]

addGroup(name,Seq,List)

class SortTagsImpl(
  child: ChildPairFactory
) extends SortTags {
  def tBodyRoot[State](handler: SortHandler[State], items: List[ChildPair[OfDiv]]): ChildPair[OfDiv] =
    child[OfDiv]("body", TBodySortRoot(items.map(_.key), handler), items)
  def handle(key: VDomKey, item: ChildPair[OfDiv]): ChildPair[OfDiv] =
    child[OfDiv](key, SortHandle(), item::Nil)
}
 */