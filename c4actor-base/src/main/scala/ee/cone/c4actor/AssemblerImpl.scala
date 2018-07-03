package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble._
import ee.cone.c4assemble.TreeAssemblerTypes.Replace
import ee.cone.c4assemble.Types._
import ee.cone.c4proto.Protocol

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
) extends ToInject {
  private def toTree(assembled: ReadModel, updates: DPIterable[Update]): ReadModel =
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
        add = if(rawValue.size > 0) composes.result(srcId,valueAdapter.decode(rawValue),+1) :: Nil else Nil
        res ← remove :: add
      } yield res)) if !composes.isEmpty(indexDiff)
    } yield {
      wKey → indexDiff
    }).seq.toMap

  private def reduce(out: Seq[Update], isParallel: Boolean): Context ⇒ Context = context ⇒ {
    val diff = toTree(context.assembled, if(isParallel) out.par else out)
    val nAssembled = TreeAssemblerKey.of(context)(diff,isParallel)(context.assembled)
    new Context(context.injected, nAssembled, context.transient)
  }

  private def add(out: Seq[Update]): Context ⇒ Context =
    if(out.isEmpty) identity[Context]
    else WriteModelKey.modify(_.enqueue(out)).andThen(reduce(out.toList, isParallel = false))

  private def getOrigIndex(context: Context, className: String): Map[SrcId,Product] = {
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
      ReadModelAddKey.set(reduce(_,isParallel)) :::
      GetOrigIndexKey.set(getOrigIndex)
}

//
case class UniqueIndexMap[K,V](index: Index)(indexUtil: IndexUtil) extends Map[K,V] {
  def +[B1 >: V](kv: (K, B1)): Map[K, B1] = throw new Exception("not implemented")//UniqueIndexMap(index + (kv._1→List(kv._2))) //diffFromJoinRes
  def get(key: K): Option[V] = Single.option(indexUtil.getValues(index,key,"")).asInstanceOf[Option[V]]
  def iterator: Iterator[(K, V)] = indexUtil.keySet(index).iterator.map{ k ⇒ (k,Single(indexUtil.getValues(index,k,""))).asInstanceOf[(K,V)] }
  def -(key: K): Map[K, V] = throw new Exception("not implemented")//UniqueIndexMap(index - key)
  override def keysIterator: Iterator[K] = indexUtil.keySet(index).iterator.asInstanceOf[Iterator[K]] // to work with non-Single
}
