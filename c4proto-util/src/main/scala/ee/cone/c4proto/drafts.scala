






package ee.cone.c4proto

import Types._
import ee.cone.c4proto.QProtocol.TopicKey



class MyReduction(indexFactory: IndexFactory) extends CoHandlerProvider {
  import indexFactory._
  def handlers = List(
    createOriginalIndex(classOf[RawChildNode]),
    createOriginalIndex(classOf[RawParentNode]),
    createJoinMapIndex(new ChildNodeByParentJoin),
    createJoinMapIndex(new ParentNodeWithChildrenJoin)
  )
}

case class RawChildNode(srcId: SrcId, parentSrcId: SrcId, caption: String)
case class RawParentNode(srcId: SrcId, caption: String)
case class ChildNodeByParent(srcId: SrcId, child: RawChildNode)
case class ParentNodeWithChildren(srcId: SrcId, children: List[RawChildNode])

class ChildNodeByParentJoin extends Join2(
  classOf[RawChildNode],
  classOf[Void],
  classOf[ChildNodeByParent]
) {
  def join(rawChildNode: Values[RawChildNode], void: Values[Void]): Values[ChildNodeByParent] =
    rawChildNode.map(child ⇒ ChildNodeByParent(srcId=child.parentSrcId,child=child))
}
class ParentNodeWithChildrenJoin extends Join2(
  classOf[ChildNodeByParent],
  classOf[RawParentNode],
  classOf[ParentNodeWithChildren]
) {
  def join(childNodeByParent: Values[ChildNodeByParent], rawParentNode: Values[RawParentNode]): Values[ParentNodeWithChildren] = {
    rawParentNode.map(parent ⇒
      ParentNodeWithChildren(parent.srcId, childNodeByParent.map(_.child).toList)
    )
  }
}

////

trait IndexFactory {
  def createJoinMapIndex(rejoin: Join): BaseCoHandler
  def createOriginalIndex[R](cl: Class[R]): BaseCoHandler
}
object Types {
  type SrcId = String
  type WorldKey = String
  type Values[V] = List[V]
  type MultiSet[T] = Map[T,Int]
  type World = Map[WorldKey,Map[Object,Values[Object]]]
}

trait Join
abstract class Join2[T1,T2,K,R](val t1: Class[T1], val t2: Class[T2], val r: Class[R]) extends Join {
  def join(a1: Values[T1], a2: Values[T2]): Values[(K,R)]
}

////
// moment -> mod/index -> key/srcId -> value -> count

case object WorldPartExpressionKey extends EventKey[WorldPartExpression]
trait WorldPartExpression {
  def inputWorldKeys: Seq[WorldKey]
  def outputWorldKey: WorldKey
  def transform(transition: WorldTransition): WorldTransition
}
case class WorldPartTransition(diff: Object, next: Object)

case class WorldTransition(prev: World, diff: Map[WorldKey,Map[Object,Boolean]], current: World)

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

class OriginalIndexPatchMap extends PatchMap[SrcId,Values[Object],Values[Object]](Nil,_.isEmpty,(v,d)⇒d)
class WorldPatchMap(
  add: OriginalIndexPatchMap = new OriginalIndexPatchMap
) extends PatchMap[WorldKey,Map[SrcId,Values[Object]],Map[SrcId,Values[Object]]](Map.empty,_.isEmpty,add.many)

class AddPatchMap[T] extends PatchMap[T,Int,Int](0,_==0,(v,d)⇒v+d)
class IndexPatchMap[AggregateKey,SortKey,Value](sortBy: Value⇒SortKey)(
  add: AddPatchMap[Value] = new AddPatchMap[Value]
)(
  implicit ord: Ordering[SortKey]
) extends PatchMap[AggregateKey,Values[Value],MultiSet[Value]](Nil,_.isEmpty,(v,d)⇒{
  add.many(d, v, 1).toList.sortBy(kv⇒sortBy(kv._1)).flatMap{ case(node,count) ⇒
    if(count<0) throw new Exception
    Seq.fill(count)(node)
  }
})
class IndexDiffMap[AggregateKey,Value](sign: Int)(
  add: AddPatchMap[Value] = new AddPatchMap[Value]
) extends PatchMap[AggregateKey,MultiSet[Value],Value](Map.empty,_.isEmpty,(v,d)⇒add.one(v, d, sign))
////


class IndexFactoryImpl extends IndexFactory {
  def createJoinMapIndex(rejoin: Join): BaseCoHandler = rejoin match {
    case join: Join2[_,_,_,_] ⇒ CoHandler(WorldPartExpressionKey)(new JoinMapIndex(
      Seq(join.t1.getName, join.t2.getName),
      join.r.getName
    )({
      case Seq(a1, a2) ⇒ join.asInstanceOf[Join2[Object,Object,Object,Object]].join(a1, a2)
    })())
  }
  def createOriginalIndex[R](cl: Class[R]): BaseCoHandler = ???
}


class JoinMapIndex[AggregateKey,SortKey,Value<:Object](
  val inputWorldKeys: Seq[WorldKey],
  val outputWorldKey: WorldKey
)(
  recalculate: Seq[Values[Object]]⇒Iterable[(AggregateKey,Value)]
)(
  val addNestedPatch: IndexPatchMap[AggregateKey,SortKey,Value] =
    new IndexPatchMap[AggregateKey,SortKey,Value](???)()(???),
  val addNestedDiff: IndexDiffMap[AggregateKey,Value] =
    new IndexDiffMap[AggregateKey,Value](+1)(),
  val subNestedDiff: IndexDiffMap[AggregateKey,Value] =
    new IndexDiffMap[AggregateKey,Value](-1)()
) extends WorldPartExpression {
  private def getPart[V](world: Map[WorldKey,Map[_,_]], key: WorldKey) =
    world.getOrElse(key, Map.empty).asInstanceOf[Map[AggregateKey,V]]
  private def setPart[V](res: Map[WorldKey,Map[Object,V]], part: Map[AggregateKey,V]) =
    res + (outputWorldKey → part.asInstanceOf[Map[Object,V]])

  def recalculateSome(
    getIndex: WorldKey⇒Map[AggregateKey,Values[Object]], add: IndexDiffMap[AggregateKey,Value],
    ids: Set[AggregateKey], res: Map[AggregateKey,MultiSet[Value]]
  ): Map[AggregateKey,MultiSet[Value]] = {
    val worldParts: Seq[Map[AggregateKey,Values[Object]]] =
      inputWorldKeys.map(getIndex)
    (res /: ids){(res: Map[AggregateKey,MultiSet[Value]], id: AggregateKey)⇒
      val args = worldParts.map(_.getOrElse(id, Nil))
      add.many(res, recalculate(args))
    }
  }
  def transform(transition: WorldTransition): WorldTransition = {
    val ids = (Set.empty[AggregateKey] /: inputWorldKeys)((res,id) ⇒
      res ++ getPart[Boolean](transition.diff, id).keys
    )
    if (ids.isEmpty){ return transition }
    val prevOutput = recalculateSome(getPart(transition.prev,_), subNestedDiff, ids, Map.empty)
    val indexDiff = recalculateSome(getPart(transition.current,_), addNestedDiff, ids, prevOutput)
    if (indexDiff.isEmpty){ return transition }
    val currentIndex: Map[AggregateKey,Values[Value]] = getPart(transition.current, outputWorldKey)
    val nextIndex: Map[AggregateKey,Values[Value]] = addNestedPatch.many(currentIndex, indexDiff)
    val next: World = setPart(transition.current, nextIndex)
    val diff = setPart(transition.diff, indexDiff.mapValues(_⇒true))
    WorldTransition(transition.prev, diff, next)
  }
}

class ReducerImpl(
  findAdapter: FindAdapter,
  handlers: List[WorldTransition⇒WorldTransition]/*CoHandlerLists*/
) {
  def reduce(prev: World, events: Iterable[QRecord]): World = {
    val keyAdapter = findAdapter.byClass(classOf[QProtocol.TopicKey])
    val evTree: Map[WorldKey,Map[SrcId,Values[Object]]] =
      events.map{
        rec ⇒(keyAdapter.decode(rec.key), rec)
      }.groupBy{
        case (topicKey,_) ⇒ topicKey.valueTypeId
      }.map{
        case (valueTypeId,keysEvents) ⇒
          val worldKey = findAdapter.nameById(valueTypeId)
          val valueAdapter = findAdapter.byId(valueTypeId)
          worldKey → keysEvents.groupBy{
            case (topicKey,_) ⇒ topicKey.srcId
          }.mapValues{ keysEvents ⇒
            val (topicKey,rec) = keysEvents.last
            val rawValue = rec.value
            if(rawValue.length>0) valueAdapter.decode(rawValue) :: Nil else Nil
          }
      }
    val diff: Map[WorldKey, Map[Object, Boolean]] =
      evTree.mapValues(_.map{ case (k,v)⇒(k:Object)→true})
    val current = (prev /: evTree){ (world, kv) ⇒
      val (worldKey,index) = kv
      ??? //world + (worldKey→)
    }

    val transition = WorldTransition(prev,diff,current)



    handlers.foldLeft(transition)((transition,handler) ⇒ handler(transition)).current
  }
}




