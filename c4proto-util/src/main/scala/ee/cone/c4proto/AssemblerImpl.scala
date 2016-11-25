
package ee.cone.c4proto

import Types._

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
  def createJoinMapIndex[T1,T2,Value<:Object,TK,MapKey](join: Join2[T1,T2,Value,TK,MapKey]): BaseCoHandler = {
    val recalculate: Seq[Values[Object]]⇒Iterable[(MapKey,Value)] = {
      case Seq(a1, a2) ⇒
        join.join(a1.asInstanceOf[Values[T1]], a2.asInstanceOf[Values[T2]])
    }
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
    CoHandler(WorldPartExpressionKey)(new JoinMapIndex[TK,MapKey,Value](
      Seq(join.t1, join.t2), join.r, recalculate,
      addNestedPatch, addNestedDiff, subNestedDiff
    ))
  }
}

class JoinMapIndex[JoinKey,MapKey,Value<:Object](
  val inputWorldKeys: Seq[WorldKey[_]],
  val outputWorldKey: IndexWorldKey[MapKey,Value],
  recalculate: Seq[Values[Object]]⇒Iterable[(MapKey,Value)],
  addNestedPatch: PatchMap[MapKey,Values[Value],MultiSet[Value]],
  addNestedDiff: PatchMap[MapKey,MultiSet[Value],Value],
  subNestedDiff: PatchMap[MapKey,MultiSet[Value],Value]
) extends WorldPartExpression {
  private def getPart[K,V](world: Map[WorldKey[_],Map[_,_]], key: WorldKey[_]) =
    world.getOrElse(key, Map.empty).asInstanceOf[Map[K,V]]
  private def setPart[V](res: Map[WorldKey[_],Map[Object,V]], part: Map[MapKey,V]) =
    res + (outputWorldKey → part.asInstanceOf[Map[Object,V]])

  def recalculateSome(
    getIndex: WorldKey[_]⇒Map[JoinKey,Values[Object]],
    add: PatchMap[MapKey,MultiSet[Value],Value],
    ids: Set[JoinKey], res: Map[MapKey,MultiSet[Value]]
  ): Map[MapKey,MultiSet[Value]] = {
    val worldParts: Seq[Map[JoinKey,Values[Object]]] =
      inputWorldKeys.map(getIndex)
    (res /: ids){(res: Map[MapKey,MultiSet[Value]], id: JoinKey)⇒
      val args = worldParts.map(_.getOrElse(id, Nil))
      add.many(res, recalculate(args))
    }
  }
  def transform(transition: WorldTransition): WorldTransition = {
    val ids = (Set.empty[JoinKey] /: inputWorldKeys)((res,id) ⇒
      res ++ getPart[JoinKey,Boolean](transition.diff, id).keys
    )
    if (ids.isEmpty){ return transition }
    val prevOutput = recalculateSome(getPart(transition.prev,_), subNestedDiff, ids, Map.empty)
    val indexDiff = recalculateSome(getPart(transition.current,_), addNestedDiff, ids, prevOutput)
    if (indexDiff.isEmpty){ return transition }
    val currentIndex: Map[MapKey,Values[Value]] = getPart(transition.current, outputWorldKey)
    val nextIndex: Map[MapKey,Values[Value]] = addNestedPatch.many(currentIndex, indexDiff)
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

object Reducer {
  def apply(handlerLists: CoHandlerLists): Reducer = {
    val replace: PatchMap[Object,Values[Object],Values[Object]] =
      new PatchMap[Object,Values[Object],Values[Object]](Nil,_.isEmpty,(v,d)⇒d)
    val add: PatchMap[WorldKey[_],Map[Object,Values[Object]],Map[Object,Values[Object]]] =
      new PatchMap[WorldKey[_],Map[Object,Values[Object]],Map[Object,Values[Object]]](Map.empty,_.isEmpty,replace.many)
    val expressions: Seq[WorldPartExpression] =
      handlerLists.list(WorldPartExpressionKey)
    val adapters = handlerLists.list(ProtocolKey).flatMap(_.adapters)
    val originals: Set[WorldKey[_]] =
      adapters.map(_.className).map(BySrcId.It(_)).toSet + VoidBy()


    val byOutput: Map[WorldKey[_], WorldPartExpression] =
      expressions.groupBy(_.outputWorldKey).mapValues{ case i :: Nil ⇒ i }
    def regOne(
        priorities: ReverseInsertionOrderSet[WorldKey[_]],
        handler: WorldPartExpression
    ): ReverseInsertionOrderSet[WorldKey[_]] = {
      val key = handler.outputWorldKey
      if(priorities.contains(key)) priorities
      else (priorities /: handler.inputWorldKeys.flatMap{ k ⇒
        val needHandler = byOutput.get(k)
        if(needHandler.isEmpty && !originals(k))
          throw new Exception(s"undefined $k")
        needHandler
      })(regOne).add(key)
    }
    val expressionsByPriority: List[WorldPartExpression] =
      (ReverseInsertionOrderSet[WorldKey[_]]() /: expressions)(regOne).items.reverse
        .map(byOutput)
    new Reducer(add, expressionsByPriority)
  }
}

class Reducer(
    add: PatchMap[WorldKey[_],Map[Object,Values[Object]],Map[Object,Values[Object]]],
    expressionsByPriority: List[WorldPartExpression]
) {
  def reduce(prev: World, replaced: World): World = {
    val diff = replaced.mapValues(_.mapValues(_⇒true))
    val current = add.many(prev, replaced)
    val transition = WorldTransition(prev,diff,current)
    expressionsByPriority.foldLeft(transition)((transition,handler) ⇒
      handler.transform(transition)
    ).current
  }
}


