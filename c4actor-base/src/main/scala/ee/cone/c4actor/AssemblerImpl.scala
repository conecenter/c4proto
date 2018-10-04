package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{Update, Updates}
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4assemble.{ToPrimaryKey, _}
import ee.cone.c4assemble.TreeAssemblerTypes.Replace
import ee.cone.c4assemble.Types._
import ee.cone.c4proto.Protocol
import okio.ByteString

import scala.annotation.tailrec
import scala.collection.GenMap
import scala.collection.immutable.{Map, Seq}

case class OrigKeyFactory(composes: IndexUtil) {
  def rawKey(className: String): AssembledKey =
    composes.joinKey(was=false, "SrcId", classOf[SrcId].getName, className)
}

case class ProtocolDataDependencies(protocols: List[Protocol], origKeyFactory: OrigKeyFactory) {
  def apply(): List[DataDependencyTo[_]] =
    protocols.flatMap(_.adapters.filter(_.hasId)).map{ adapter ⇒
      new OriginalWorldPart(origKeyFactory.rawKey(adapter.className))
    }
}

case object TreeAssemblerKey extends SharedComponentKey[Replace]

class AssemblerInit(
  qAdapterRegistry: QAdapterRegistry,
  toUpdate: ToUpdate,
  treeAssembler: TreeAssembler,
  getDependencies: ()⇒List[DataDependencyTo[_]],
  isParallel: Boolean,
  composes: IndexUtil,
  origKeyFactory: OrigKeyFactory
) extends ToInject with LazyLogging {

  private def toTree(assembled: ReadModel, updates: DPIterable[Update], fix: (SrcId,Product)⇒Product = (_,i)⇒i): ReadModel =
    (for {
      tpPair ← updates.groupBy(_.valueTypeId)
      (valueTypeId, tpUpdates) = tpPair
      valueAdapter ← qAdapterRegistry.byId.get(valueTypeId)
      wKey = origKeyFactory.rawKey(valueAdapter.className)
      wasIndex = wKey.of(assembled)
      indexDiff ← Option(composes.mergeIndex(for {
        iPair ← tpUpdates.groupBy(_.srcId)
        (srcId, iUpdates) = iPair
        rawValue = iUpdates.last.value
        remove = composes.removingDiff(wasIndex,srcId)
        add = if(rawValue.size > 0) composes.result(srcId,fix(srcId,valueAdapter.decode(rawValue)),+1) :: Nil else Nil
        res ← remove :: add
      } yield res)) if !composes.isEmpty(indexDiff)
    } yield {
      wKey → indexDiff
    }).seq.toMap

  // read model part:
  private def toTree(assembled: ReadModel, events: DPIterable[RawEvent]): ReadModel = {
    val updates = events.map(ev⇒Update(ev.srcId, qAdapterRegistry.updatesAdapter.id, ev.data))
    toTree(assembled, updates, (id,item)⇒item.asInstanceOf[Updates].copy(srcId=id))
  }
  private def getValues[T](cl: Class[T], readModel: ReadModel): List[T] = {
    val index = origKeyFactory.rawKey(cl.getName).of(readModel)
    for{
      o ← composes.keySet(index).map{ case o: String ⇒ o }.toList.sorted
      item ← composes.getValues(index,o,"")
    } yield item.asInstanceOf[T]
  }
  private def joinDiffs(a: ReadModel, b: ReadModel): ReadModel = {
    val r = a ++ b
    assert(a.size + b.size == r.size)
    r
  }
  private def reduceAndClearMeta(replace: Replace, wasAssembled: ReadModel, diff: ReadModel): ReadModel = {
    val assembled = replace(diff,isParallel)(wasAssembled)
    val mUpdates = getValues(classOf[ClearUpdates],assembled).map(_.updates)
      .flatMap(LEvent.delete).map(toUpdate.toUpdate)
    if(mUpdates.isEmpty) assembled
    else reduceAndClearMeta(replace, assembled, toTree(assembled, mUpdates))
  }
  private def readModelAdd(replace: Replace): Seq[RawEvent]⇒ReadModel⇒ReadModel = events ⇒ assembled ⇒ try {
    val metaDiff = toTree(assembled, events)
    val updates = getValues(classOf[Updates],metaDiff).flatMap(_.updates)
    val realDiff = toTree(assembled, if(isParallel) updates.par else updates)
    val end = NanoTimer()
    val nAssembled = reduceAndClearMeta(replace, assembled, joinDiffs(realDiff,metaDiff))
    val period = end.ms
    if(period > 1000) logger.info(s"long join $period ms")
    nAssembled
  } catch {
    case e: Exception ⇒
      logger.error("reduce", e) // ??? exception to record
      if(events.size == 1){
        val metaDiff = toTree(assembled, events)
        val updates = events.map(ev⇒FailedUpdates(ev.srcId))
          .flatMap(LEvent.update).map(toUpdate.toUpdate)
        val failDiff = toTree(assembled, updates)
        reduceAndClearMeta(replace, assembled, joinDiffs(failDiff,metaDiff))
      } else {
        val(a,b) = events.splitAt(events.size / 2)
        Function.chain(Seq(readModelAdd(replace)(a), readModelAdd(replace)(b)))(assembled)
      }
  }
  // other parts:
  private def add(out: Seq[Update]): Context ⇒ Context =
    if(out.isEmpty) identity[Context]
    else WriteModelKey.modify(_.enqueue(out)).andThen { local ⇒
      val diff = toTree(local.assembled, out)
      val replace = TreeAssemblerKey.of(local)
      val assembled = replace(diff,false)(local.assembled)
      new Context(local.injected, assembled, local.transient) //call add here for new mortal?
    }

  private def getOrigIndex(context: AssembledContext, className: String): Map[SrcId,Product] = {
    UniqueIndexMap(origKeyFactory.rawKey(className).of(context.assembled))(composes)
  }

  def toInject: List[Injectable] =
    TreeAssemblerKey.set(treeAssembler.replace(getDependencies())) :::
      WriteModelDebugAddKey.set(out ⇒
        if(out.isEmpty) identity[Context]
        else WriteModelDebugKey.modify(_.enqueue(out))
          .andThen(add(out.map(toUpdate.toUpdate)))
      ) :::
      WriteModelAddKey.set(add) :::
      ReadModelAddKey.set(context⇒readModelAdd(TreeAssemblerKey.of(context))) :::
      GetOrigIndexKey.set(getOrigIndex)
}

case class UniqueIndexMap[K,V](index: Index)(indexUtil: IndexUtil) extends Map[K,V] {
  def +[B1 >: V](kv: (K, B1)): Map[K, B1] = iterator.toMap + kv
  def get(key: K): Option[V] = Single.option(indexUtil.getValues(index,key,"")).asInstanceOf[Option[V]]
  def iterator: Iterator[(K, V)] = indexUtil.keySet(index).iterator.map{ k ⇒ (k,Single(indexUtil.getValues(index,k,""))).asInstanceOf[(K,V)] }
  def -(key: K): Map[K, V] = iterator.toMap - key
  override def keysIterator: Iterator[K] = indexUtil.keySet(index).iterator.asInstanceOf[Iterator[K]] // to work with non-Single
}

//@assemble