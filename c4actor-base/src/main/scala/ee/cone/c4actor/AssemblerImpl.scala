
package ee.cone.c4actor

import Types._
import ee.cone.c4actor.TreeAssemblerTypes.Replace
import ee.cone.c4proto.Protocol

import scala.collection.immutable.Map

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

class IndexFactoryImpl extends IndexFactory {
  def createJoinMapIndex[Value<:Object,TK,MapKey](join: Join[Value,TK,MapKey]):
    WorldPartExpression
      with DataDependencyFrom[Index[TK, Object]]
      with DataDependencyTo[Index[MapKey, Value]]
  = {
    val add: PatchMap[Value,Int,Int] =
      new PatchMap[Value,Int,Int](0,_==0,(v,d)⇒v+d)
    val addNestedPatch: PatchMap[MapKey,Values[Value],MultiSet[Value]] =
      new PatchMap[MapKey,Values[Value],MultiSet[Value]](
        Nil,_.isEmpty,
        (v,d)⇒join.sort(add.many(d, v, 1).flatMap{ case(node,count) ⇒
          if(count<0) throw new Exception
          List.fill(count)(node)
        })
      )
    val addNestedDiff: PatchMap[MapKey,MultiSet[Value],Value] =
      new PatchMap[MapKey,MultiSet[Value],Value](Map.empty,_.isEmpty,(v,d)⇒add.one(v, d, +1))
    val subNestedDiff: PatchMap[MapKey,MultiSet[Value],Value] =
      new PatchMap[MapKey,MultiSet[Value],Value](Map.empty,_.isEmpty,(v,d)⇒add.one(v, d, -1))
    new JoinMapIndex[TK,MapKey,Value](
      join, addNestedPatch, addNestedDiff, subNestedDiff
    )
  }
}

class JoinMapIndex[JoinKey,MapKey,Value<:Object](
  join: Join[Value,JoinKey,MapKey],
  addNestedPatch: PatchMap[MapKey,Values[Value],MultiSet[Value]],
  addNestedDiff: PatchMap[MapKey,MultiSet[Value],Value],
  subNestedDiff: PatchMap[MapKey,MultiSet[Value],Value]
) extends WorldPartExpression
  with DataDependencyFrom[Index[JoinKey, Object]]
  with DataDependencyTo[Index[MapKey, Value]]
{
  def inputWorldKeys: Seq[WorldKey[Index[JoinKey, Object]]] = join.inputWorldKeys
  def outputWorldKey: WorldKey[Index[MapKey, Value]] = join.outputWorldKey

  private def setPart[V](res: World, part: Map[MapKey,V]) =
    (res + (outputWorldKey → part)).asInstanceOf[Map[WorldKey[_],Map[Object,V]]]

  def recalculateSome(
    getIndex: WorldKey[Index[JoinKey,Object]]⇒Index[JoinKey,Object],
    add: PatchMap[MapKey,MultiSet[Value],Value],
    ids: Set[JoinKey], res: Map[MapKey,MultiSet[Value]]
  ): Map[MapKey,MultiSet[Value]] = {
    val worldParts: Seq[Index[JoinKey,Object]] =
      inputWorldKeys.map(getIndex)
    (res /: ids){(res: Map[MapKey,MultiSet[Value]], id: JoinKey)⇒
      val args = worldParts.map(_.getOrElse(id, Nil))
      add.many(res, join.joins(args))
    }
  }
  def transform(transition: WorldTransition): WorldTransition = {
    val ids = (Set.empty[JoinKey] /: inputWorldKeys)((res,key) ⇒
      res ++ transition.diff.getOrElse(key, Map.empty).keys.asInstanceOf[Set[JoinKey]]
    )
    if (ids.isEmpty){ return transition }
    val prevOutput = recalculateSome(_.of(transition.prev), subNestedDiff, ids, Map.empty)
    val indexDiff = recalculateSome(_.of(transition.current), addNestedDiff, ids, prevOutput)
    if (indexDiff.isEmpty){ return transition }
    val currentIndex: Index[MapKey,Value] = outputWorldKey.of(transition.current)
    val nextIndex: Index[MapKey,Value] = addNestedPatch.many(currentIndex, indexDiff)
    val next: World = setPart(transition.current, nextIndex)
    val diff = setPart(transition.diff, indexDiff.mapValues(_⇒true))
    WorldTransition(transition.prev, diff, next)
  }
}

case class ReverseInsertionOrderSet[T](contains: Set[T]=Set.empty[T], items: List[T]=Nil) {
  def add(item: T): ReverseInsertionOrderSet[T] = {
    if(contains(item)) throw new Exception(s"has $item")
    ReverseInsertionOrderSet(contains + item, item :: items)
  }
}

object TreeAssemblerImpl extends TreeAssembler {
  def replace: List[DataDependencyTo[_]] ⇒ Replace = rules ⇒ {
    val replace: PatchMap[Object,Values[Object],Values[Object]] =
      new PatchMap[Object,Values[Object],Values[Object]](Nil,_.isEmpty,(v,d)⇒d)
    val add =
      new PatchMap[WorldKey[_],Index[Object,Object],Index[Object,Object]](Map.empty,_.isEmpty,replace.many)
          .asInstanceOf[PatchMap[WorldKey[_],Object,Index[Object,Object]]]
    val expressions/*: Seq[WorldPartExpression]*/ =
      rules.collect{ case e: WorldPartExpression with DataDependencyTo[_] with DataDependencyFrom[_] ⇒ e }
      //handlerLists.list(WorldPartExpressionKey)
    val originals: Set[WorldKey[_]] =
      rules.collect{ case e: OriginalWorldPart[_] ⇒ e.outputWorldKey }.toSet
    val byOutput: Map[WorldKey[_], Seq[WorldPartExpression with DataDependencyFrom[_]]] =
      expressions.groupBy(_.outputWorldKey)
    def regOne(
        priorities: ReverseInsertionOrderSet[WorldPartExpression with DataDependencyFrom[_]],
        handler: WorldPartExpression with DataDependencyFrom[_]
    ): ReverseInsertionOrderSet[WorldPartExpression with DataDependencyFrom[_]] = {
      if(priorities.contains(handler)) priorities
      else (priorities /: handler.inputWorldKeys.flatMap{ k ⇒
        byOutput.getOrElse(k,
          if(originals(k)) Nil else throw new Exception(s"undefined $k")
        )
      })(regOne).add(handler)
    }
    val expressionsByPriority: List[WorldPartExpression] =
      (ReverseInsertionOrderSet[WorldPartExpression with DataDependencyFrom[_]]() /: expressions)(regOne).items.reverse
    replaced ⇒ prevWorld ⇒ {
      val diff = replaced.mapValues(_.mapValues(_⇒true))
      val current = add.many(prevWorld, replaced)
      val transition = WorldTransition(prevWorld,diff,current)
      (transition /: expressionsByPriority)((transition,handler) ⇒
        handler.transform(transition)
      ).current
    }
  }
}

object ProtocolDataDependencies {
  def apply(protocols: List[Protocol]): List[DataDependencyTo[_]] =
    protocols.flatMap(_.adapters).map(adapter⇒new OriginalWorldPart(By.It('S',adapter.className)))
}
