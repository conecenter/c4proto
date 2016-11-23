






package ee.cone.c4proto

import Types._




class MyReduction(indexFactory: IndexFactory) extends CoHandlerProvider {
  import indexFactory._
  def handlers = List(
    //createOriginalIndex(classOf[RawChildNode]),
    //createOriginalIndex(classOf[RawParentNode]),
    createJoinMapIndex(new ChildNodeByParentJoin),
    createJoinMapIndex(new ParentNodeWithChildrenJoin)
  )
}

case class RawChildNode(srcId: SrcId, parentSrcId: SrcId, caption: String)
case class RawParentNode(srcId: SrcId, caption: String)
case object ChildNodeByParent extends IndexWorldKey[SrcId,RawChildNode]
case class ParentNodeWithChildren(caption: String, children: List[RawChildNode])

class ChildNodeByParentJoin extends Join2(
  BySrcId(classOf[RawChildNode]), VoidBy[SrcId](), ChildNodeByParent
) {
  def join(rawChildNode: Values[RawChildNode], void: Values[Unit]): Values[(SrcId,RawChildNode)] =
    rawChildNode.map(child ⇒ child.parentSrcId → child)
  def sort(nodes: Iterable[RawChildNode]): List[RawChildNode] =
    nodes.toList.sortBy(_.srcId)
}
class ParentNodeWithChildrenJoin extends Join2(
  ChildNodeByParent, BySrcId(classOf[RawParentNode]), BySrcId(classOf[ParentNodeWithChildren])
) {
  def join(
      childNodeByParent: Values[RawChildNode],
      rawParentNode: Values[RawParentNode]
  ): Values[(SrcId,ParentNodeWithChildren)] = {
    rawParentNode.map(parent ⇒
      parent.srcId → ParentNodeWithChildren(parent.caption, childNodeByParent)
    )
  }
  def sort(nodes: Iterable[ParentNodeWithChildren]): List[ParentNodeWithChildren] =
    if(nodes.size <= 1) nodes.toList else throw new Exception("PK")
}

////

trait WorldKey[Item]
case class VoidBy[K]() extends IndexWorldKey[K,Unit]
object BySrcId {
  case class It[V](className: String) extends IndexWorldKey[SrcId,V]
  def apply[V](cl: Class[V]): IndexWorldKey[SrcId,V] = It(cl.getName)
}


trait IndexFactory {
  def createJoinMapIndex[T1,T2,R<:Object,TK,RK](join: Join2[T1,T2,R,TK,RK]): BaseCoHandler
}
object Types {
  type IndexWorldKey[K,V] = WorldKey[Map[K,Values[V]]]
  type SrcId = String
  //type WorldKey = String
  type Values[V] = List[V]
  type MultiSet[T] = Map[T,Int]
  type World = Map[WorldKey[_],Map[Object,Values[Object]]]
}

abstract class Join2[T1,T2,R,TK,RK](
    val t1: IndexWorldKey[TK,T1],
    val t2: IndexWorldKey[TK,T2],
    val r: IndexWorldKey[RK,R]
) {
  def join(a1: Values[T1], a2: Values[T2]): Values[(RK,R)]
  def sort(values: Iterable[R]): List[R]
}

////
// moment -> mod/index -> key/srcId -> value -> count

case object WorldPartExpressionKey extends EventKey[WorldPartExpression]
trait WorldPartExpression {
  def inputWorldKeys: Seq[WorldKey[_]]
  def outputWorldKey: WorldKey[_]
  def transform(transition: WorldTransition): WorldTransition
}
case class WorldPartTransition(diff: Object, next: Object)

case class WorldTransition(prev: World, diff: Map[WorldKey[_],Map[Object,Boolean]], current: World)

////

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



////


class IndexFactoryImpl extends IndexFactory {
  def createJoinMapIndex[T1,T2,R<:Object,TK,RK](join: Join2[T1,T2,R,TK,RK]): BaseCoHandler = {
    CoHandler(WorldPartExpressionKey)(new JoinMapIndex[TK,RK,R](
      Seq(join.t1, join.t2), join.r
    )(
      {
        case Seq(a1, a2) ⇒ join.join(a1.asInstanceOf[Values[T1]], a2.asInstanceOf[Values[T2]])
      },
      join.sort
    )()())
  }
}


class JoinMapIndex[JoinKey,MapKey,Value<:Object](
  val inputWorldKeys: Seq[WorldKey[_]],
  val outputWorldKey: IndexWorldKey[MapKey,Value]
)(
  recalculate: Seq[Values[Object]]⇒Iterable[(MapKey,Value)],
  sort: Iterable[Value] ⇒ List[Value]
)(
  val add: PatchMap[Value,Int,Int] = new PatchMap[Value,Int,Int](0,_==0,(v,d)⇒v+d)
)(
  val addNestedPatch: PatchMap[MapKey,Values[Value],MultiSet[Value]] =
    new PatchMap[MapKey,Values[Value],MultiSet[Value]](
      Nil,_.isEmpty,
      (v,d)⇒sort(add.many(d, v, 1).flatMap{ case(node,count) ⇒
        if(count<0) throw new Exception
        List.fill(count)(node)
      })
    ),
  val addNestedDiff: PatchMap[MapKey,MultiSet[Value],Value] =
    new PatchMap[MapKey,MultiSet[Value],Value](Map.empty,_.isEmpty,(v,d)⇒add.one(v, d, +1)),
  val subNestedDiff: PatchMap[MapKey,MultiSet[Value],Value] =
    new PatchMap[MapKey,MultiSet[Value],Value](Map.empty,_.isEmpty,(v,d)⇒add.one(v, d, -1))
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

class ReducerImpl(
  handlerLists: CoHandlerLists
)(
  val replace: PatchMap[Object,Values[Object],Values[Object]] =
    new PatchMap[Object,Values[Object],Values[Object]](Nil,_.isEmpty,(v,d)⇒d)
)(
  val add: PatchMap[WorldKey[_],Map[Object,Values[Object]],Map[Object,Values[Object]]] =
    new PatchMap[WorldKey[_],Map[Object,Values[Object]],Map[Object,Values[Object]]](Map.empty,_.isEmpty,replace.many)
) {
  private lazy val handlers: List[WorldTransition⇒WorldTransition] = {
    val list = handlerLists.list(WorldPartExpressionKey)
    val byOutput = list.groupBy(_.outputWorldKey).mapValues{ case i :: Nil ⇒ i }
    (Map.empty[WorldKey[_],Int] /: list){(priorities,expression)⇒reg(priorities,key)}

    def reg(res: Map[WorldKey[_],Int], keys: Seq[WorldKey[_]]): Map[WorldKey[_],Int] = (res /: keys){
      (priorities,key) ⇒
        val handler = byOutput(key)
        ???
        val res = reg(priorities, handler.inputWorldKeys)
        res + (key→res.size)
    }

    ???
  }
  def reduce(prev: World, replaced: World): World = {
    val diff = replaced.mapValues(_.mapValues(_⇒true))
    val current = add.many(prev, replaced)
    val transition = WorldTransition(prev,diff,current)
    handlers.foldLeft(transition)((transition,handler) ⇒ handler(transition)).current
  }
}

class QRecords(findAdapter: FindAdapter) {
  def toTree(records: Iterable[QRecord]): World = {
    val keyAdapter = findAdapter.byClass(classOf[QProtocol.TopicKey])
    records.map {
      rec ⇒ (keyAdapter.decode(rec.key), rec)
    }.groupBy {
      case (topicKey, _) ⇒ topicKey.valueTypeId
    }.map {
      case (valueTypeId, keysEvents) ⇒
        val worldKey = BySrcId.It(findAdapter.nameById(valueTypeId))
        val valueAdapter = findAdapter.byId(valueTypeId)
        worldKey → keysEvents.groupBy {
          case (topicKey, _) ⇒ topicKey.srcId
        }.map { case(srcId,keysEventsI) ⇒
          val (topicKey, rec) = keysEventsI.last
          val rawValue = rec.value
          (srcId:Object) → (if (rawValue.length > 0) valueAdapter.decode(rawValue) :: Nil else Nil)
        }
    }
  }
  def fromTree(diff: World): Seq[QRecord] = ???

}


