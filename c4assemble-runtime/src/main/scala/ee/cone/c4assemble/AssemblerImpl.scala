
package ee.cone.c4assemble

// see Topological sorting

//import java.util.Comparator

import Types._
import ee.cone.c4assemble.HiddenC4Annotations.c4component
import ee.cone.c4assemble.TreeAssemblerTypes.{MultiSet, Replace}

import scala.collection.immutable.{Iterable, Map, Seq}

class PatchMap[K,V,DV](empty: V, isEmpty: V⇒Boolean, op: (V,DV)⇒V) {
  def one(res: Map[K,V], key: K, diffV: DV): Map[K,V] = {
    val prevV = res.getOrElse(key,empty)
    val nextV = op(prevV,diffV)
    if(isEmpty(nextV)) res - key else res + (key → nextV)
  }
  def many(res: Map[K,V], keys: Iterable[K], value: DV): Map[K,V] =
    (res /: keys)((res, key) ⇒ one(res, key, value))
  def many(res: Map[K,V], diff: Iterable[(K,DV)]): Map[K,V] =
    (res /: diff)((res, kv) ⇒ one(res, kv._1, kv._2))
}

@c4component case class IndexFactoryImpl(
  merger: IndexValueMergerFactory,
  profiler: AssembleProfiler
) extends IndexFactory {
  def createJoinMapIndex[T,R<:Product,TK,RK](join: Join[T,R,TK,RK]):
    WorldPartExpression
      with DataDependencyFrom[Index[TK, T]]
      with DataDependencyTo[Index[RK, R]]
  = {
    val add: PatchMap[R,Int,Int] =
      new PatchMap[R,Int,Int](0,_==0,(v,d)⇒v+d)
    val merge = merger.create[R]
    val addNestedPatch: PatchMap[RK,Values[R],MultiSet[R]] =
      new PatchMap[RK,Values[R],MultiSet[R]](Nil,_.isEmpty, merge)
    val addNestedDiff: PatchMap[RK,MultiSet[R],R] =
      new PatchMap[RK,MultiSet[R],R](Map.empty,_.isEmpty,(v,d)⇒add.one(v, d, +1))
    val subNestedDiff: PatchMap[RK,MultiSet[R],R] =
      new PatchMap[RK,MultiSet[R],R](Map.empty,_.isEmpty,(v,d)⇒add.one(v, d, -1))
    new JoinMapIndex[T,TK,RK,R](
      join, addNestedPatch, addNestedDiff, subNestedDiff, profiler.get(join.name)
    )
  }
}

class JoinMapIndex[T,JoinKey,MapKey,Value<:Product](
  join: Join[T,Value,JoinKey,MapKey],
  addNestedPatch: PatchMap[MapKey,Values[Value],MultiSet[Value]],
  addNestedDiff: PatchMap[MapKey,MultiSet[Value],Value],
  subNestedDiff: PatchMap[MapKey,MultiSet[Value],Value],
  profiler: String ⇒ Int ⇒ Unit
) extends DataDependencyTo[Index[MapKey, Value]]
  with DataDependencyFrom[Index[JoinKey, T]]
  with WorldPartExpression
{
  def assembleName = join.assembleName
  def name = join.name
  def inputWorldKeys: Seq[AssembledKey[Index[JoinKey, T]]] = join.inputWorldKeys
  def outputWorldKey: AssembledKey[Index[MapKey, Value]] = join.outputWorldKey
  override def toString: String = s"${super.toString} ($assembleName,$name,$inputWorldKeys,$outputWorldKey)"

  private def setPart[V](res: ReadModel, part: Map[MapKey,V]) =
    (res + (outputWorldKey → part)).asInstanceOf[Map[AssembledKey[_],Map[Object,V]]]

  def recalculateSome(
    getIndex: AssembledKey[Index[JoinKey,T]]⇒Index[JoinKey,T],
    add: PatchMap[MapKey,MultiSet[Value],Value],
    ids: Set[JoinKey], res: Map[MapKey,MultiSet[Value]]
  ): Map[MapKey,MultiSet[Value]] = {
    val worldParts: Seq[Index[JoinKey,T]] =
      inputWorldKeys.map(getIndex)
    (res /: ids){(res: Map[MapKey,MultiSet[Value]], id: JoinKey)⇒
      val args = worldParts.map(_.getOrElse(id, Nil))
      add.many(res, join.joins(id, args))
    }
  }

  def transform(transition: WorldTransition): WorldTransition = {
    //println(s"rule $outputWorldKey <- $inputWorldKeys")
    val ids = (Set.empty[JoinKey] /: inputWorldKeys)((res,key) ⇒
      res ++ transition.diff.getOrElse(key, Map.empty).keys.asInstanceOf[Set[JoinKey]]
    )
    if (ids.isEmpty) transition else transform(transition,ids)
  }
  private def transform(transition: WorldTransition, ids: Set[JoinKey]): WorldTransition = {
    val end = profiler("calculate")
    val prevOutput = recalculateSome(_.of(transition.prev), subNestedDiff, ids, Map.empty)
    val indexDiff = recalculateSome(_.of(transition.current), addNestedDiff, ids, prevOutput)
    end(ids.size)
    if(indexDiff.isEmpty) transition else patch(transition, indexDiff)
  }
  private def patch(transition: WorldTransition, indexDiff: Map[MapKey, MultiSet[Value]]): WorldTransition = {
    val end = profiler("patch    ")

    val currentIndex: Index[MapKey,Value] = outputWorldKey.of(transition.current)
    val nextIndex: Index[MapKey,Value] = addNestedPatch.many(currentIndex, indexDiff)
    val next: ReadModel = setPart(transition.current, nextIndex)

    val currentDiff = transition.diff.getOrElse(outputWorldKey,Map.empty).asInstanceOf[Map[MapKey, Boolean]]
    val nextDiff: Map[MapKey, Boolean] = currentDiff ++ indexDiff.transform((_,_)⇒true)
    val diff = setPart(transition.diff, nextDiff)

    end(indexDiff.size)
    WorldTransition(transition.prev, diff, next)
  }
}

@c4component case class NoAssembleProfiler() extends AssembleProfiler {
  def get(ruleName: String): String ⇒ Int ⇒ Unit = dummy
  private def dummy(startAction: String)(finalCount: Int): Unit = ()
}

//case class AssembleProfiling(key: String, tp: String, count: Int, period: Long)

/*
class ByPriority[Item](uses: Item⇒List[Item]){
  private def regOne(res: ReverseInsertionOrder[Item,Item], item: Item): ReverseInsertionOrder[Item,Item] =
    if(res.map.contains(item)) res else (res /: uses(item))(regOne).add(item,item)
  def apply(items: List[Item]): List[Item] =
    (ReverseInsertionOrder[Item,Item]() /: items)(regOne).values.reverse
}
*/

@c4component case class TreeAssemblerImpl(byPriority: ByPriority, expressionsDumpers: List[UnitExpressionsDumper]) extends TreeAssembler {
  def replace: List[DataDependencyTo[_]] ⇒ Replace = rules ⇒ {
    val replace: PatchMap[Object,Values[Object],Values[Object]] =
      new PatchMap[Object,Values[Object],Values[Object]](Nil,_.isEmpty,(v,d)⇒d)
    val add =
      new PatchMap[AssembledKey[_],Index[Object,Object],Index[Object,Object]](Map.empty,_.isEmpty,replace.many)
          .asInstanceOf[PatchMap[AssembledKey[_],Object,Index[Object,Object]]]
    val expressions/*: Seq[WorldPartExpression]*/ =
      rules.collect{ case e: WorldPartExpression with DataDependencyTo[_] with DataDependencyFrom[_] ⇒ e }
      //handlerLists.list(WorldPartExpressionKey)
    val originals: Set[AssembledKey[_]] =
      rules.collect{ case e: OriginalWorldPart[_] ⇒ e.outputWorldKey }.toSet
    //umlClients.foreach(_(s"# rules: ${rules.size}, originals: ${originals.size}, expressions: ${expressions.size}"))
    val byOutput: Map[AssembledKey[_], Seq[WorldPartExpression with DataDependencyFrom[_]]] =
      expressions.groupBy(_.outputWorldKey)
    val expressionsByPriority: List[WorldPartExpression] =
      byPriority.byPriority[
        WorldPartExpression with DataDependencyFrom[_],
        WorldPartExpression with DataDependencyFrom[_]
      ](item⇒(
        item.inputWorldKeys.flatMap{ k ⇒
          byOutput.getOrElse(k,
            if(originals(k)) Nil else throw new Exception(s"undefined $k in $originals")
          )
        }.toList,
        _ ⇒ item
      ))(expressions).reverse

    expressionsDumpers.foreach(_.dump(expressionsByPriority.map{
      case e: DataDependencyTo[_] with DataDependencyFrom[_] ⇒ e
    }))

    replaced ⇒ prevWorld ⇒ {
      val diff = replaced.transform((k,v)⇒v.transform((_,_)⇒true))
      val current = add.many(prevWorld, replaced)
      val transition = WorldTransition(prevWorld,diff,current)
      (transition /: expressionsByPriority) { (transition, handler) ⇒
        handler.transform(transition)
      }.current
    }
  }
}

@c4component case class UMLExpressionsDumperImpl() extends UMLExpressionsDumper {
  def dump(expressions: List[DataDependencyTo[_] with DataDependencyFrom[_]]): String = {
    val keyAliases: List[(AssembledKey[_], String)] =
      expressions.flatMap[AssembledKey[_],List[AssembledKey[_]]](e ⇒ e.outputWorldKey :: e.inputWorldKeys.toList)
        .distinct.zipWithIndex.map{ case (k,i) ⇒ (k,s"wk$i")}
    val keyToAlias: Map[AssembledKey[_], String] = keyAliases.toMap
    List(
      for((k:Product,a) ← keyAliases) yield
        s"(${k.productElement(0)} ${k.productElement(2).toString.split("[\\$\\.]").last}) as $a",
      for((e,eIndex) ← expressions.zipWithIndex; k ← e.inputWorldKeys)
        yield s"${keyToAlias(k)} --> $eIndex-${e.name}",
      for((e,eIndex) ← expressions.zipWithIndex)
        yield s"$eIndex-${e.name} --> ${keyToAlias(e.outputWorldKey)}"
    ).flatten.mkString("@startuml\n","\n","\n@enduml")
  }
}


