package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble._
import ee.cone.c4assemble.TreeAssemblerTypes.Replace
import ee.cone.c4assemble.Types._
import ee.cone.c4proto.Protocol

import scala.collection.immutable.Seq

object ProtocolDataDependencies {
  def apply(protocols: List[Protocol]): List[DataDependencyTo[_]] =
    protocols.flatMap(_.adapters.filter(_.hasId)).map{ adapter ⇒
      new OriginalWorldPart(ByPK.raw(adapter.className))
    }
}

case object TreeAssemblerKey extends SharedComponentKey[Replace]

class AssemblerInit(
  qAdapterRegistry: QAdapterRegistry,
  toUpdate: ToUpdate,
  treeAssembler: TreeAssembler,
  getDependencies: ()⇒List[DataDependencyTo[_]],
  isParallel: Boolean,
  composes: IndexUtil
) extends ToInject {
  private def toTree(assembled: ReadModel, updates: DPIterable[Update]): ReadModel =
    (for {
      (valueTypeId, tpUpdates) ← updates.groupBy(_.valueTypeId)
      valueAdapter ← qAdapterRegistry.byId.get(valueTypeId)
      wKey = ByPK.raw[Product](valueAdapter.className)
      wasIndex = wKey.of(assembled)
      indexDiff ← Option(composes.mergeIndex(for {
        (srcId, iUpdates) ← tpUpdates.groupBy(_.srcId)
        rawValue = iUpdates.last.value
        add = if(rawValue.size > 0) composes.result(srcId,valueAdapter.decode(rawValue),+1) :: Nil else Nil
        res ← composes.removingDiff(wasIndex,srcId) :: add
      } yield res)) if indexDiff.keySet.nonEmpty
    } yield wKey → indexDiff).seq.toMap

  private def reduce(out: Seq[Update], isParallel: Boolean): Context ⇒ Context = context ⇒ {
    val diff = toTree(context.assembled, if(isParallel) out.par else out)
    val nAssembled = TreeAssemblerKey.of(context)(diff,isParallel)(context.assembled)
    new Context(context.injected, nAssembled, context.transient)
  }

  private def add(out: Seq[Update]): Context ⇒ Context =
    if(out.isEmpty) identity[Context]
    else WriteModelKey.modify(_.enqueue(out)).andThen(reduce(out.toList, isParallel = false))
  def toInject: List[Injectable] =
    TreeAssemblerKey.set(treeAssembler.replace(getDependencies())) :::
      WriteModelDebugAddKey.set(out ⇒
        if(out.isEmpty) identity[Context]
        else WriteModelDebugKey.modify(_.enqueue(out))
          .andThen(add(out.map(toUpdate.toUpdate)))
      ) :::
      WriteModelAddKey.set(add) :::
      ReadModelAddKey.set(reduce(_,isParallel))
}
