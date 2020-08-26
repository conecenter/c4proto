package ee.cone.c4generator

import scala.meta._

object TagGenerator extends Generator {
  case class Param(paramName: String, paramTypeName: String)


  def get(parseContext: ParseContext): List[Generated] = parseContext.stats.flatMap{
    case Defn.Trait(Seq(mod"@c4tags"),Type.Name(traitName),x,y,code) =>
      code.stats.flatMap{
        case defDef@q"def $defName(...$args): VDom[${Type.Name(outTypeName)}]" =>

          val argByIsChild = args.flatten.map{
            case p@param"${Name(paramName)}: VDom[${Type.Name(paramTypeName)}]*" =>
              (true,Param(paramName, paramTypeName))
            case p@param"${Name(paramName)}: ${Type.Name(paramTypeName)} = $paramExprOpt" =>
              (false,Param(paramName, paramTypeName))
          }.groupMap(_._1)(_._2)
          val childArgs = argByIsChild.getOrElse(true,Nil)
          val attrArgs = argByIsChild.getOrElse(false,Nil)



          ???
      }
      ???
    case _ => Nil
  }


}

/*

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